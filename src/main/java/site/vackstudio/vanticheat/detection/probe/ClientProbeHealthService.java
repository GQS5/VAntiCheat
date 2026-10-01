package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.platform.ClientPlatform;
import site.vackstudio.vanticheat.platform.ClientPlatformService;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, per-player record of observable client-probe readiness.
 *
 * <p>Every field here is something VAntiCheat can actually observe about the probing
 * channel. Nothing in this class is a statement about hardware, device performance, or
 * cheating, and nothing here can produce a detection.
 */
public final class ClientProbeHealthService {
    public static final int TRACKED_PLAYER_LIMIT = 512;
    public static final Duration ENTRY_TTL = Duration.ofMinutes(30);

    public record Entry(UUID playerId, String playerName, ClientPlatform platform,
                        ClientProbeHealth health, String detail, long responseMillis,
                        Instant observedAt) {
    }

    public record Snapshot(Map<ClientProbeHealth, Long> counts, int trackedPlayers,
                           long observations, int expiredEntries) {
        public Snapshot {
            counts = java.util.Collections.unmodifiableMap(new EnumMap<>(counts));
        }

        public long count(ClientProbeHealth health) { return counts.getOrDefault(health, 0L); }
    }

    private final ConcurrentMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<ClientProbeHealth, LongAdder> counters = new EnumMap<>(ClientProbeHealth.class);
    private final LongAdder observations = new LongAdder();

    public ClientProbeHealthService() {
        for (ClientProbeHealth health : ClientProbeHealth.values()) counters.put(health, new LongAdder());
    }

    /** Records platform readiness before any probe runs. */
    public void observePlatform(UUID playerId, String playerName, ClientPlatformService.Classification classification) {
        Objects.requireNonNull(classification, "classification");
        ClientProbeHealth health = switch (classification.state()) {
            case JAVA, NO_PROVIDER -> ClientProbeHealth.READY;
            case BEDROCK -> ClientProbeHealth.UNSUPPORTED;
            case UNKNOWN -> ClientProbeHealth.INTERRUPTED;
        };
        record(playerId, playerName, classification.platform(), health,
                "platform=" + classification.state() + " reason=" + classification.reason(), -1L);
    }

    /** Records the observable outcome of one transport batch. */
    public void observeBatch(UUID playerId, String playerName, ClientPlatform platform,
                             ProbeTransportMode transport, ProbeResponse.Outcome outcome, long responseMillis) {
        Objects.requireNonNull(outcome, "outcome");
        ClientProbeHealth health = ClientProbeHealth.from(outcome);
        String detail = "transport=" + transport + " outcome=" + outcome
                + (responseMillis >= 0 ? " responseMillis=" + responseMillis : "");
        record(playerId, playerName, platform, health, detail, responseMillis);
    }

    /**
     * Refines health after evaluation. A responding client that returned no usable identity
     * is {@link ClientProbeHealth#UNRESPONSIVE}: the channel works, the content did not.
     */
    public void observeEvaluation(UUID playerId, String playerName, ClientPlatform platform,
                                  ProbeEvaluation evaluation, long responseMillis) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (evaluation.status() == DetectionStatus.TIMEOUT) {
            record(playerId, playerName, platform, ClientProbeHealth.TIMED_OUT,
                    "response deadline elapsed", responseMillis);
            return;
        }
        if (evaluation.status() == DetectionStatus.UNSUPPORTED) {
            record(playerId, playerName, platform, ClientProbeHealth.UNSUPPORTED,
                    "transport cannot carry this probe", responseMillis);
            return;
        }
        if (evaluation.status() == DetectionStatus.UNCERTAIN) {
            record(playerId, playerName, platform, ClientProbeHealth.UNRESPONSIVE,
                    "client answered without a resolvable identity", responseMillis);
            return;
        }
        record(playerId, playerName, platform, ClientProbeHealth.RESPONSIVE,
                "client answered the probe", responseMillis);
    }

    private void record(UUID playerId, String playerName, ClientPlatform platform,
                        ClientProbeHealth health, String detail, long responseMillis) {
        if (playerId == null || health == null) return;
        counters.get(health).increment();
        observations.increment();
        entries.put(playerId, new Entry(playerId, playerName, platform, health, detail,
                responseMillis, Instant.now()));
        evict();
    }

    public Entry get(UUID playerId) {
        Entry entry = playerId == null ? null : entries.get(playerId);
        if (entry == null) return null;
        if (isExpired(entry)) {
            entries.remove(playerId, entry);
            return null;
        }
        return entry;
    }

    public int trackedPlayers() { return entries.size(); }

    private int lastExpired;

    private void evict() {
        while (entries.size() > TRACKED_PLAYER_LIMIT) {
            Entry oldest = null;
            for (Entry candidate : entries.values()) {
                if (oldest == null || candidate.observedAt().isBefore(oldest.observedAt())) oldest = candidate;
            }
            if (oldest == null || !entries.remove(oldest.playerId(), oldest)) return;
            lastExpired++;
        }
    }

    private static boolean isExpired(Entry entry) {
        return entry.observedAt().plus(ENTRY_TTL).isBefore(Instant.now());
    }

    public Snapshot snapshot() {
        int expired = lastExpired;
        entries.entrySet().removeIf(entry -> isExpired(entry.getValue()));
        EnumMap<ClientProbeHealth, Long> counts = new EnumMap<>(ClientProbeHealth.class);
        counters.forEach((health, count) -> counts.put(health, count.sum()));
        return new Snapshot(counts, entries.size(), observations.sum(), expired);
    }

    public void remove(UUID playerId) {
        if (playerId != null) entries.remove(playerId);
    }

    public void clear() {
        entries.clear();
        lastExpired = 0;
        observations.reset();
        counters.values().forEach(LongAdder::reset);
    }
}
