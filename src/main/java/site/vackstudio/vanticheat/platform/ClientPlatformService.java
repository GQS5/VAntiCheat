package site.vackstudio.vanticheat.platform;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BiConsumer;

/** Cheap, cached classification using authoritative optional platform providers. */
public final class ClientPlatformService {
    public enum State { JAVA, BEDROCK, UNKNOWN, NO_PROVIDER }
    public enum Readiness { READY, NOT_READY, DEGRADED }
    public enum ProviderState { READY, NOT_PRESENT, NOT_READY, FAILED }
    public enum Reason { FLOODGATE, GEYSER, BOTH, NO_BEDROCK_PROVIDER, PROVIDER_NOT_READY, JAVA_CONFIRMED }

    public record Classification(State state, Reason reason, String source) {
        public boolean canProbe() { return state == State.JAVA || state == State.NO_PROVIDER; }
        public ClientPlatform platform() {
            return switch (state) {
                case BEDROCK -> ClientPlatform.BEDROCK;
                case UNKNOWN -> ClientPlatform.UNKNOWN;
                case JAVA, NO_PROVIDER -> ClientPlatform.JAVA;
            };
        }
    }

    public record ProviderSnapshot(ProviderState floodgate, ProviderState geyser, Readiness readiness) { }

    public interface Provider {
        String name();
        boolean installed();
        default boolean ready() { return installed(); }
        default ProviderState status() {
            try {
                if (!installed()) return ProviderState.NOT_PRESENT;
                return ready() ? ProviderState.READY : ProviderState.NOT_READY;
            } catch (LinkageError | RuntimeException exception) {
                return ProviderState.FAILED;
            }
        }
        /** Returns null if its API is not ready or cannot authoritatively answer. */
        Boolean isBedrock(UUID playerId);
    }

    private final Provider floodgate;
    private final Provider geyser;
    private final ConcurrentMap<UUID, Classification> cache = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BiConsumer<UUID, Classification>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLongArray stateCounts = new AtomicLongArray(State.values().length);
    private volatile ProviderSnapshot providerSnapshot;

    public ClientPlatformService(Provider floodgate, Provider geyser) {
        this.floodgate = Objects.requireNonNull(floodgate, "floodgate");
        this.geyser = Objects.requireNonNull(geyser, "geyser");
        providerSnapshot = readProviderSnapshot();
    }

    public Classification classify(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return cache.computeIfAbsent(playerId, id -> {
            Classification queried = query(id);
            stateCounts.incrementAndGet(queried.state().ordinal());
            return queried;
        });
    }

    /** Classifies pre-login UUIDs without retaining state for logins that are later rejected. */
    public Classification inspect(UUID playerId) {
        return query(Objects.requireNonNull(playerId, "playerId"));
    }

    /** Re-queries providers and publishes authoritative transitions. */
    public Classification refresh(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Classification next = query(playerId);
        Classification previous;
        synchronized (this) {
            previous = cache.put(playerId, next);
            replaceCount(previous, next);
        }
        if (!next.equals(previous)) listeners.forEach(listener -> listener.accept(playerId, next));
        return next;
    }

    private Classification query(UUID playerId) {
        ProviderState floodgateState = safeProviderState(floodgate);
        ProviderState geyserState = safeProviderState(geyser);
        boolean floodgatePresent = floodgateState != ProviderState.NOT_PRESENT;
        boolean geyserPresent = geyserState != ProviderState.NOT_PRESENT;
        if (!floodgatePresent && !geyserPresent) {
            providerSnapshot = snapshot(floodgateState, geyserState);
            return new Classification(State.NO_PROVIDER, Reason.NO_BEDROCK_PROVIDER, "NO_BEDROCK_PROVIDER");
        }
        Boolean floodgateBedrock = floodgateState == ProviderState.READY ? safeQuery(floodgate, playerId) : null;
        Boolean geyserBedrock = geyserState == ProviderState.READY ? safeQuery(geyser, playerId) : null;
        if (floodgatePresent && floodgateBedrock == null) floodgateState = safeProviderState(floodgate);
        if (geyserPresent && geyserBedrock == null) geyserState = safeProviderState(geyser);
        providerSnapshot = snapshot(floodgateState, geyserState);
        if (Boolean.TRUE.equals(floodgateBedrock) || Boolean.TRUE.equals(geyserBedrock)) {
            if (Boolean.TRUE.equals(floodgateBedrock) && Boolean.TRUE.equals(geyserBedrock)) {
                return new Classification(State.BEDROCK, Reason.BOTH, "FLOODGATE+GEYSER");
            }
            return Boolean.TRUE.equals(floodgateBedrock)
                    ? new Classification(State.BEDROCK, Reason.FLOODGATE, floodgate.name())
                    : new Classification(State.BEDROCK, Reason.GEYSER, geyser.name());
        }
        boolean allReady = (!floodgatePresent || floodgateBedrock != null)
                && (!geyserPresent || geyserBedrock != null);
        if (allReady) return new Classification(State.JAVA, Reason.JAVA_CONFIRMED, "PROVIDERS");
        return new Classification(State.UNKNOWN, Reason.PROVIDER_NOT_READY,
                readinessReason(floodgateState, geyserState, floodgateBedrock, geyserBedrock));
    }

    private static Boolean safeQuery(Provider provider, UUID playerId) {
        try { return provider.isBedrock(playerId); }
        catch (LinkageError | RuntimeException ignored) { return null; }
    }

    private static String readinessReason(ProviderState floodgate, ProviderState geyser,
                                          Boolean floodgateAnswer, Boolean geyserAnswer) {
        String floodgateReason = floodgate == ProviderState.FAILED ? "FLOODGATE_FAILED" : "FLOODGATE_NOT_READY";
        String geyserReason = geyser == ProviderState.FAILED ? "GEYSER_FAILED" : "GEYSER_NOT_READY";
        boolean floodgatePending = floodgate != ProviderState.NOT_PRESENT && floodgateAnswer == null;
        boolean geyserPending = geyser != ProviderState.NOT_PRESENT && geyserAnswer == null;
        if (floodgatePending && geyserPending) return floodgateReason + "+" + geyserReason;
        return floodgatePending ? floodgateReason : geyserReason;
    }

    public void addListener(BiConsumer<UUID, Classification> listener) { listeners.add(Objects.requireNonNull(listener)); }
    public void removeListener(BiConsumer<UUID, Classification> listener) { listeners.remove(listener); }
    public Classification get(UUID playerId) { return cache.get(playerId); }
    public synchronized void put(UUID playerId, Classification classification) {
        replaceCount(cache.put(playerId, classification), classification);
    }
    public synchronized void invalidate(UUID playerId) { remove(playerId); }
    public synchronized void remove(UUID playerId) {
        Classification previous = cache.remove(playerId);
        if (previous != null) stateCounts.decrementAndGet(previous.state().ordinal());
    }
    public synchronized void clear() {
        cache.clear();
        for (int i = 0; i < State.values().length; i++) stateCounts.set(i, 0);
    }
    public int size() { return cache.size(); }
    public long count(State state) { return stateCounts.get(state.ordinal()); }

    public ProviderSnapshot providerSnapshot() { return providerSnapshot; }

    public String providers() {
        ProviderSnapshot snapshot = providerSnapshot;
        boolean f = snapshot.floodgate() != ProviderState.NOT_PRESENT;
        boolean g = snapshot.geyser() != ProviderState.NOT_PRESENT;
        return f && g ? "GEYSER + FLOODGATE" : f ? "FLOODGATE" : g ? "GEYSER" : "NONE";
    }

    public Readiness readiness() { return providerSnapshot.readiness(); }

    public Map<UUID, Classification> snapshot() { return Map.copyOf(cache); }

    private void replaceCount(Classification previous, Classification next) {
        if (previous != null && previous.state() == next.state()) return;
        if (previous != null) stateCounts.decrementAndGet(previous.state().ordinal());
        stateCounts.incrementAndGet(next.state().ordinal());
    }

    private ProviderSnapshot readProviderSnapshot() {
        ProviderState floodgateState = safeProviderState(floodgate);
        ProviderState geyserState = safeProviderState(geyser);
        return snapshot(floodgateState, geyserState);
    }

    private static ProviderSnapshot snapshot(ProviderState floodgateState, ProviderState geyserState) {
        int present = (floodgateState == ProviderState.NOT_PRESENT ? 0 : 1)
                + (geyserState == ProviderState.NOT_PRESENT ? 0 : 1);
        int ready = (floodgateState == ProviderState.READY ? 1 : 0)
                + (geyserState == ProviderState.READY ? 1 : 0);
        Readiness overall = present == 0 || ready == present ? Readiness.READY
                : ready == 0 ? Readiness.NOT_READY : Readiness.DEGRADED;
        return new ProviderSnapshot(floodgateState, geyserState, overall);
    }

    private static ProviderState safeProviderState(Provider provider) {
        try {
            ProviderState state = provider.status();
            return state == null ? ProviderState.FAILED : state;
        } catch (LinkageError | RuntimeException exception) {
            return ProviderState.FAILED;
        }
    }
}
