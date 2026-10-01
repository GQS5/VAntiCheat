package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Bounded, read-only scan history and low-cost completion metrics. */
public final class ProbeDiagnostics {
    public static final int RECENT_SCAN_LIMIT = 100;

    public record ActiveScan(UUID sessionId, UUID playerId, String playerName, String trigger,
                             int probeCount, int batchIndex, String pass, List<String> currentProbes,
                             Instant startedAt, long elapsedMillis) {
        public ActiveScan { currentProbes = List.copyOf(currentProbes); }
    }

    public record RecentScan(Instant completedAt, UUID playerId, String playerName, String trigger,
                             int probeCount, List<String> detectedProbeIds, DetectionStatus result,
                             long durationMillis) {
        public RecentScan { detectedProbeIds = List.copyOf(detectedProbeIds); }
    }

    /**
     * Why a scan concluded the way it did. Every field is a count or a fixed reason code;
     * no raw response payload is retained.
     */
    public record Coverage(long eligible, long passive, long interactive, long skippedByPolicy,
                           long transportOutcomes, long weakEvidence, long strongEvidence,
                           long detected, long ambiguous, long confirmed, long notPromoted) {
        public static Coverage empty() {
            return new Coverage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record Snapshot(Map<DetectionStatus, Long> results, long completedScans,
                           long lastDurationMillis, long averageDurationMillis,
                           List<ActiveScan> activeScans, List<RecentScan> recentScans,
                           Coverage coverage, Map<String, Long> conclusionReasons) {
        public Snapshot {
            results = java.util.Collections.unmodifiableMap(new EnumMap<>(results));
            activeScans = List.copyOf(activeScans);
            recentScans = List.copyOf(recentScans);
            conclusionReasons = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(conclusionReasons));
        }

        public long count(DetectionStatus status) { return results.getOrDefault(status, 0L); }
        public long timeouts() { return count(DetectionStatus.TIMEOUT); }
        public long errors() { return count(DetectionStatus.ERROR); }
        public long detections() { return count(DetectionStatus.DETECTED); }
        public long cleans() { return count(DetectionStatus.CLEAN); }
        public long protectedResults() { return count(DetectionStatus.PROTECTED); }
    }

    private final EnumMap<DetectionStatus, LongAdder> resultCounters = new EnumMap<>(DetectionStatus.class);
    private final Map<ProbeEvidenceStrength, LongAdder> evidenceCounters =
            new EnumMap<>(ProbeEvidenceStrength.class);
    private final Map<String, LongAdder> conclusionReasons = new java.util.concurrent.ConcurrentHashMap<>();
    private final LongAdder eligible = new LongAdder();
    private final LongAdder passive = new LongAdder();
    private final LongAdder interactive = new LongAdder();
    private final LongAdder skippedByPolicy = new LongAdder();
    private final LongAdder transportOutcomes = new LongAdder();
    private final LongAdder detected = new LongAdder();
    private final LongAdder ambiguous = new LongAdder();
    private final LongAdder confirmed = new LongAdder();
    private final LongAdder notPromoted = new LongAdder();
    private final ConcurrentMap<UUID, MutableActive> active = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<RecentScan> recent = new ConcurrentLinkedDeque<>();
    private final AtomicInteger recentSize = new AtomicInteger();
    private final Object historyLock = new Object();
    private final LongAdder completedScans = new LongAdder();
    private final LongAdder durationTotalMillis = new LongAdder();
    private final AtomicLong lastDurationMillis = new AtomicLong();

    public ProbeDiagnostics() {
        for (DetectionStatus status : DetectionStatus.values()) resultCounters.put(status, new LongAdder());
        for (ProbeEvidenceStrength strength : ProbeEvidenceStrength.values()) {
            evidenceCounters.put(strength, new LongAdder());
        }
    }

    /** Records how many probes were eligible and how their transports were distributed. */
    public void selection(List<ProbeDefinition> probes) {
        eligible.add(probes.size());
        for (ProbeDefinition probe : probes) {
            if (probe.transport() == ProbeTransportMode.PASSIVE) passive.increment();
            else interactive.increment();
        }
    }

    public void skippedByPolicy(int count) {
        skippedByPolicy.add(count);
    }

    public void transportOutcome() {
        transportOutcomes.increment();
    }

    /** Records a scan conclusion and, when it was not promoted, why. */
    public void concluded(ProbeCorroboration.Decision decision) {
        if (decision == null) return;
        conclusionReasons.computeIfAbsent(decision.conclusion().name(), key -> new LongAdder()).increment();
        switch (decision.conclusion()) {
            case CONFIRMED -> confirmed.increment();
            case AMBIGUOUS -> {
                ambiguous.increment();
                notPromoted.add(decision.weakSignals());
            }
            case NO_TARGET -> notPromoted.increment();
            default -> { }
        }
    }

    public void evidence(ProbeEvidenceStrength strength) {
        if (strength == null) return;
        evidenceCounters.get(strength).increment();
        if (strength == ProbeEvidenceStrength.STRONG) detected.increment();
    }

    public Coverage coverage() {
        return new Coverage(eligible.sum(), passive.sum(), interactive.sum(),
                skippedByPolicy.sum(), transportOutcomes.sum(),
                evidenceCounters.get(ProbeEvidenceStrength.WEAK).sum(),
                evidenceCounters.get(ProbeEvidenceStrength.STRONG).sum(),
                detected.sum(), ambiguous.sum(), confirmed.sum(), notPromoted.sum());
    }

    public Map<String, Long> conclusionReasons() {
        Map<String, Long> snapshot = new java.util.TreeMap<>();
        conclusionReasons.forEach((key, value) -> snapshot.put(key, value.sum()));
        return snapshot;
    }

    public void scanStarted(UUID sessionId, UUID playerId, String playerName, String trigger,
                            int probeCount, long startedNanos) {
        active.put(sessionId, new MutableActive(sessionId, playerId, playerName, diagnosticTrigger(trigger),
                probeCount, Instant.now(), startedNanos));
    }

    public void batchStarted(UUID sessionId, int batchIndex, String pass, List<String> probeIds) {
        MutableActive scan = active.get(sessionId);
        if (scan != null) {
            scan.batchIndex = batchIndex;
            scan.pass = pass;
            scan.currentProbes = List.copyOf(probeIds);
        }
    }

    public void scanCompleted(UUID sessionId, DetectionResult result, long completedNanos) {
        MutableActive scan = active.remove(sessionId);
        if (scan == null) return;
        resultCounters.get(result.status()).increment();
        completedScans.increment();
        long durationMillis = scan == null ? 0L
                : Math.max(0L, (completedNanos - scan.startedNanos) / 1_000_000L);
        lastDurationMillis.set(durationMillis);
        durationTotalMillis.add(durationMillis);
        RecentScan entry = new RecentScan(Instant.now(), scan.playerId, scan.playerName, scan.trigger,
                scan.probeCount, CheckHacksClientDetectionModule.detectedProbeIds(result),
                result.status(), durationMillis);
        appendRecent(entry);
    }

    private void appendRecent(RecentScan entry) {
        synchronized (historyLock) {
            recent.addFirst(entry);
            int size = recentSize.incrementAndGet();
            while (size > RECENT_SCAN_LIMIT) {
                recent.removeLast();
                size = recentSize.decrementAndGet();
            }
        }
    }

    /** Bounded history for policy skips that never created an engine session. */
    public void standaloneScan(UUID playerId, String playerName, String trigger, int probeCount,
                               DetectionStatus status) {
        resultCounters.get(status).increment();
        appendRecent(new RecentScan(Instant.now(), playerId, playerName, diagnosticTrigger(trigger),
                probeCount, List.of(), status, 0));
    }

    public void clearActive() { active.clear(); }

    public void clearHistory() {
        synchronized (historyLock) {
            recent.clear();
            recentSize.set(0);
        }
    }

    public void scanCancelled(UUID sessionId) { active.remove(sessionId); }

    private static String diagnosticTrigger(String trigger) {
        return "JOIN".equalsIgnoreCase(trigger) ? "AUTOMATIC" : trigger;
    }

    public Snapshot snapshot() {
        EnumMap<DetectionStatus, Long> results = new EnumMap<>(DetectionStatus.class);
        resultCounters.forEach((status, count) -> results.put(status, count.sum()));
        List<ActiveScan> activeSnapshot = active.values().stream()
                .map(MutableActive::snapshot)
                .sorted(java.util.Comparator.comparing(ActiveScan::startedAt))
                .toList();
        List<RecentScan> recentSnapshot;
        synchronized (historyLock) {
            recentSnapshot = List.copyOf(new ArrayList<>(recent));
        }
        long completed = completedScans.sum();
        return new Snapshot(results, completed, lastDurationMillis.get(),
                completed == 0 ? 0 : durationTotalMillis.sum() / completed,
                activeSnapshot, recentSnapshot, coverage(), conclusionReasons());
    }

    private static final class MutableActive {
        private final UUID sessionId;
        private final UUID playerId;
        private final String playerName;
        private final String trigger;
        private final int probeCount;
        private final Instant startedAt;
        private final long startedNanos;
        private volatile int batchIndex;
        private volatile String pass = "INITIAL";
        private volatile List<String> currentProbes = List.of();

        private MutableActive(UUID sessionId, UUID playerId, String playerName, String trigger,
                              int probeCount, Instant startedAt, long startedNanos) {
            this.sessionId = sessionId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.trigger = trigger;
            this.probeCount = probeCount;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
        }

        private ActiveScan snapshot() {
            return new ActiveScan(sessionId, playerId, playerName, trigger, probeCount,
                    batchIndex, pass, currentProbes, startedAt,
                    Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
        }
    }
}
