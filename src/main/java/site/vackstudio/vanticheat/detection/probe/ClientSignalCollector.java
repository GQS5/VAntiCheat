package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.platform.ClientPlatform;
import site.vackstudio.vanticheat.platform.ClientPlatformService;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, connection-scoped store of passive client signals.
 *
 * <p>Design constraints this satisfies:
 * <ul>
 *   <li>UUID scoped, so a signal can never be attributed to a different player.</li>
 *   <li>Connection scoped: a reconnect installs a fresh context, so evidence from a
 *       previous connection can never leak into the new one.</li>
 *   <li>Bounded per player (a small set of distinct probe ids) and bounded in player
 *       count, so memory cannot grow with traffic.</li>
 *   <li>TTL expiring, and explicitly cleared on disconnect.</li>
 *   <li>Stores no raw payload bytes, only the channel name, probe id, and a length.</li>
 *   <li>Never consults or mutates the world, the player, or any UI.</li>
 * </ul>
 *
 * <p>It is deliberately not a score. It records which declared channel was seen; the
 * evidence model, not a tally, decides any conclusion.
 */
public final class ClientSignalCollector {
    public static final int MAX_PLAYERS = 512;
    public static final int MAX_SIGNALS_PER_PLAYER = 32;
    public static final Duration TTL = Duration.ofMinutes(10);

    public record Entry(UUID playerId, long generation, ClientPlatform platform,
                        Set<String> probeIds, String brand, Instant observedAt) {
        public Entry {
            probeIds = Collections.unmodifiableSet(new LinkedHashSet<>(probeIds));
        }
    }

    public record Snapshot(long observed, long dropped, long rejected, int trackedPlayers,
                           Map<String, Long> perChannel) {
        public Snapshot {
            perChannel = Collections.unmodifiableMap(new java.util.TreeMap<>(perChannel));
        }
    }

    /** Availability of the passive observation path itself. */
    public enum State { READY, DISABLED, UNAVAILABLE, ERROR }

    private final Map<String, String> declaredChannels;
    private final ConcurrentMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> generations = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> perChannel = new java.util.concurrent.ConcurrentHashMap<>();
    private final LongAdder observed = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private volatile State state = State.DISABLED;

    public ClientSignalCollector() {
        this(Map.of());
    }

    /**
     * @param declaredChannels the complete channel-to-probe allow-list. An observation whose
     *                         channel is not declared for the probe it claims is refused, so
     *                         no caller can introduce a prefix, suffix, or mismatched pairing.
     */
    public ClientSignalCollector(Map<String, String> declaredChannels) {
        this.declaredChannels = Map.copyOf(new java.util.LinkedHashMap<>(declaredChannels));
    }

    public java.util.Set<String> declaredChannels() {
        return declaredChannels.keySet();
    }

    public State state() { return state; }

    public void setState(State state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Installs a fresh, empty context for a connection. Returns the new generation. */
    public long beginConnection(UUID playerId, ClientPlatform platform, String brand) {
        Objects.requireNonNull(playerId, "playerId");
        long generation = generations.compute(playerId, (id, previous) -> previous == null ? 1L : previous + 1L);
        entries.put(playerId, new Entry(playerId, generation,
                platform == null ? ClientPlatform.UNKNOWN : platform, Set.of(), brand, Instant.now()));
        evict();
        return generation;
    }

    /**
     * Records one observed channel. A signal for a stale generation is rejected, so a late
     * packet from a previous connection cannot contaminate the current one.
     */
    public void observe(UUID playerId, long generation, String channel, String probeId,
                        int bytes, ClientPlatform platform, String brand) {
        if (playerId == null || channel == null || probeId == null) return;
        // Defence in depth: the collector itself refuses any channel that was not declared
        // for that probe, so no caller can introduce a prefix, suffix, or mismatched pairing.
        if (!probeId.equals(declaredChannels.get(channel))) {
            rejected.increment();
            return;
        }
        perChannel.computeIfAbsent(channel, key -> new LongAdder()).increment();
        boolean accepted = entries.compute(playerId, (id, current) -> {
            if (current == null) return null;
            if (current.generation() != generation) return current;
            if (current.probeIds().size() >= MAX_SIGNALS_PER_PLAYER
                    && !current.probeIds().contains(probeId)) {
                return current;
            }
            LinkedHashSet<String> merged = new LinkedHashSet<>(current.probeIds());
            merged.add(probeId);
            return new Entry(id, generation,
                    platform == null ? current.platform() : platform, merged,
                    brand == null ? current.brand() : brand, Instant.now());
        }) != null;
        if (accepted) {
            observed.increment();
        } else {
            rejected.increment();
        }
    }

    public Entry entry(UUID playerId) {
        Entry entry = playerId == null ? null : entries.get(playerId);
        if (entry == null) return null;
        if (entry.observedAt().plus(TTL).isBefore(Instant.now())) {
            entries.remove(playerId, entry);
            return null;
        }
        return entry;
    }

    public boolean has(UUID playerId, String probeId) {
        Entry entry = entry(playerId);
        return entry != null && entry.probeIds().contains(probeId);
    }

    public int trackedPlayers() { return entries.size(); }

    public void remove(UUID playerId) {
        if (playerId == null) return;
        entries.remove(playerId);
        generations.remove(playerId);
    }

    public void clear() {
        entries.clear();
        generations.clear();
        perChannel.clear();
        observed.reset();
        dropped.reset();
        rejected.reset();
    }

    private void evict() {
        while (entries.size() > MAX_PLAYERS) {
            Entry oldest = null;
            for (Entry candidate : entries.values()) {
                if (oldest == null || candidate.observedAt().isBefore(oldest.observedAt())) oldest = candidate;
            }
            if (oldest == null || !entries.remove(oldest.playerId(), oldest)) return;
            dropped.increment();
        }
    }

    public Snapshot snapshot() {
        Map<String, Long> channels = new java.util.TreeMap<>();
        perChannel.forEach((channel, count) -> channels.put(channel, count.sum()));
        return new Snapshot(observed.sum(), dropped.sum(), rejected.sum(), entries.size(), channels);
    }

    /** Diagnostics helper: health counts keyed by platform classification. */
    public Map<ClientPlatformService.State, Long> platformCounts() {
        EnumMap<ClientPlatformService.State, Long> counts = new EnumMap<>(ClientPlatformService.State.class);
        entries.values().forEach(entry ->
                counts.merge(platformOf(entry.platform()), 1L, Long::sum));
        return counts;
    }

    private static ClientPlatformService.State platformOf(ClientPlatform platform) {
        return switch (platform) {
            case JAVA -> ClientPlatformService.State.JAVA;
            case BEDROCK -> ClientPlatformService.State.BEDROCK;
            case UNKNOWN -> ClientPlatformService.State.UNKNOWN;
        };
    }
}
