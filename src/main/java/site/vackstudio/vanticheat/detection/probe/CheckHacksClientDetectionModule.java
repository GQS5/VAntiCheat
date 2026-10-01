package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.detection.DetectionCategory;
import site.vackstudio.vanticheat.detection.DetectionDefinition;
import site.vackstudio.vanticheat.detection.DetectionModule;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionSession;
import site.vackstudio.vanticheat.detection.DetectionSeverity;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.detection.Evidence;
import site.vackstudio.vanticheat.detection.EvidenceType;
import site.vackstudio.vanticheat.platform.Scheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class CheckHacksClientDetectionModule implements DetectionModule {
    public static final String ID = "client-detection.checkhacks";
    private static final int MAX_PENDING_TIMELINES = 100;
    private volatile ClientDetectionConfig configuration;
    private final ClientProbeTransport transport;
    private final ConcurrentMap<UUID, DetectionSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProbeHandle> activeHandles = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> sessionStarts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Consumer<DetectionResult>> terminalCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, CompletedScan> completedScans = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UUID> completedScanOrder = new ConcurrentLinkedDeque<>();
    private final ProbeDiagnostics diagnostics = new ProbeDiagnostics();
    private DetectionModuleContext context;
    private volatile boolean started;
    private volatile ProbeTimeline timeline = new ProbeTimeline(null, false);
    private volatile Predicate<UUID> probeEligibility = ignored -> true;

    public CheckHacksClientDetectionModule(ClientDetectionConfig configuration, ClientProbeTransport transport) {
        this.configuration = configuration;
        this.transport = transport;
    }

    @Override
    public site.vackstudio.vanticheat.detection.DetectionDefinition definition() {
        return new DetectionDefinition(ID, "Client Detection", "CheckHacks-derived client translation probes",
                DetectionCategory.CLIENT, DetectionSeverity.MEDIUM, configuration.enabled());
    }

    @Override public String version() { return "checkhacks-1.3.1-adapter"; }

    @Override public void initialize(DetectionModuleContext context) {
        this.context = context;
        this.timeline = new ProbeTimeline(context.logger(), context.configuration().debug());
    }

    @Override public void start() {
        started = true;
    }

    public DetectionSession check(DetectionTarget target, Consumer<DetectionResult> result) {
        ClientDetectionConfig scanConfiguration = configuration;
        return startCheck(target, scanConfiguration.manualProbes(), "MANUAL", result, false,
                scanConfiguration);
    }

    public DetectionSession check(DetectionTarget target, List<ProbeDefinition> probes,
                                  Consumer<DetectionResult> result) {
        return startCheck(target, probes, "MANUAL", result, false, configuration);
    }

    public DetectionSession checkIfIdle(DetectionTarget target, List<ProbeDefinition> probes,
                                        String trigger, Consumer<DetectionResult> result) {
        return startCheck(target, probes, trigger, result, true, configuration);
    }

    /** Captures config and automatic registry policy atomically at scan admission. */
    public DetectionSession checkAutomaticIfIdle(DetectionTarget target, String trigger,
                                                  Consumer<DetectionResult> result) {
        ClientDetectionConfig scanConfiguration = configuration;
        if (!scanConfiguration.autoCheckOnJoin()) {
            standaloneResult(target.id(), target.name(), "JOIN", 0, DetectionStatus.SKIPPED,
                    "automatic join checks disabled", result);
            return null;
        }
        return startCheck(target, scanConfiguration.automaticProbes(), trigger, result, true,
                scanConfiguration);
    }

    public boolean isActive(UUID targetId) {
        return activeSessions.containsKey(targetId);
    }

    public boolean isStarted() {
        return started && configuration.enabled();
    }

    public int activeSessionCount() {
        return activeSessions.size();
    }

    public ProbeDiagnostics.Snapshot diagnostics() { return diagnostics.snapshot(); }

    public List<ProbeDefinition> probes() {
        return configuration.probes();
    }

    public List<ProbeDefinition> enabledProbes() {
        return configuration.probeRegistry().enabled();
    }

    public List<ProbeDefinition> manualProbes() {
        return configuration.manualProbes();
    }

    public List<ProbeDefinition> automaticProbes() {
        return configuration.automaticProbes();
    }

    public ProbeRegistry registry() {
        return configuration.probeRegistry();
    }

    /** Atomically publishes a validated configuration for new sessions. */
    public void replaceConfiguration(ClientDetectionConfig replacement) {
        if (replacement == null) throw new IllegalArgumentException("replacement cannot be null");
        configuration = replacement;
    }

    public void setProbeEligibility(Predicate<UUID> probeEligibility) {
        this.probeEligibility = java.util.Objects.requireNonNull(probeEligibility, "probeEligibility");
    }

    public String displayName(String probeId) {
        ProbeDefinition probe = configuration.probeRegistry().find(probeId);
        return probe == null ? probeId : probe.displayName();
    }

    public static List<String> detectedProbeIds(DetectionResult result) {
        List<String> confirmed = result.evidence().stream()
                .filter(item -> "CONFIRMATION".equals(item.metadata().get("pass")))
                .filter(item -> "DETECTED".equals(item.metadata().get("classification")))
                .map(item -> item.metadata().get("probe"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (!confirmed.isEmpty()) return confirmed;
        return result.evidence().stream()
                .filter(item -> "DETECTED".equals(item.metadata().get("classification")))
                .map(item -> item.metadata().get("probe"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Uses the display metadata captured by each session, not the registry after a reload. */
    public static List<String> detectedProbeDisplayNames(DetectionResult result) {
        boolean hasConfirmed = result.evidence().stream()
                .anyMatch(item -> "CONFIRMATION".equals(item.metadata().get("pass"))
                        && "DETECTED".equals(item.metadata().get("classification")));
        String selectedPass = hasConfirmed ? "CONFIRMATION" : "INITIAL";
        return result.evidence().stream()
                .filter(item -> selectedPass.equals(item.metadata().get("pass")))
                .filter(item -> "DETECTED".equals(item.metadata().get("classification")))
                .map(item -> item.metadata().getOrDefault("displayName", item.metadata().get("probe")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private DetectionSession startCheck(DetectionTarget target, List<ProbeDefinition> configuredProbes,
                                        String trigger, Consumer<DetectionResult> result, boolean onlyIfIdle,
                                        ClientDetectionConfig scanConfiguration) {
        if (!started) {
            standaloneResult(target.id(), target.name(), trigger, configuredProbes.size(),
                    DetectionStatus.SKIPPED, "client detection is not started", result);
            return null;
        }
        boolean eligible;
        try {
            eligible = probeEligibility.test(target.id());
        } catch (RuntimeException exception) {
            standaloneResult(target.id(), target.name(), trigger, configuredProbes.size(),
                    DetectionStatus.ERROR, "client platform classification failed", result);
            return null;
        }
        if (!eligible) {
            standaloneResult(target.id(), target.name(), trigger, configuredProbes.size(),
                    DetectionStatus.SKIPPED, "client platform is not eligible for probing", result);
            return null;
        }
        if (!scanConfiguration.enabled()) {
            standaloneResult(target.id(), target.name(), trigger, configuredProbes.size(),
                    DetectionStatus.SKIPPED, "client detection disabled", result);
            return null;
        }
        List<ProbeDefinition> probes = configuredProbes.stream()
                .filter(probe -> probe.enabled()
                        && ("JOIN".equals(trigger) ? probe.automatic() : probe.manual()))
                .distinct().toList();
        if (probes.isEmpty()) {
            standaloneResult(target.id(), target.name(), trigger, 0, DetectionStatus.SKIPPED,
                    "no " + ("JOIN".equals(trigger) ? "automatic" : "manual") + " probes configured", result);
            return null;
        }
        DetectionSession existing = activeSessions.get(target.id());
        if (existing != null) {
            if (onlyIfIdle) return null;
            return existing;
        }
        DetectionSession session = new DetectionSession(UUID.randomUUID(), target.id(), ID, Instant.now());
        if (activeSessions.putIfAbsent(target.id(), session) != null) {
            return onlyIfIdle ? null : activeSessions.get(target.id());
        }
        long scanStart = timeline.start();
        sessionStarts.put(session.sessionId(), scanStart);
        diagnostics.scanStarted(session.sessionId(), target.id(), target.name(), trigger,
                probes.size(), scanStart);
        timeline.event("SCAN_START", scanStart, session.sessionId(), target.id(), trigger, 0,
                configuredProbes.size(), null);
        sessionTrigger.put(session.sessionId(), trigger);
        terminalCallbacks.put(session.sessionId(), result);
        session.start();
        timeline.event("SELECTION_START", scanStart, session.sessionId(), target.id(), trigger, 0,
                configuredProbes.size(), null);
        timeline.event("SELECTION_END", scanStart, session.sessionId(), target.id(), trigger, 0,
                probes.size(), null);
        if (context != null && context.configuration().debug()) {
            context.logger().info("[ClientProbe] SELECT trigger=" + trigger
                    + " session=" + session.sessionId() + " player=" + target.name()
                    + " uuid=" + target.id() + " probes=" + probeSummary(probes));
        }
        runPass(session, target, probes, scanConfiguration, ProbePass.INITIAL, new ArrayList<>(), 0, first -> {
            DetectionStatus firstStatus = ProbeResultAggregator.aggregate(first.statuses());
            List<ProbeDefinition> flagged = new ArrayList<>();
            for (int i = 0; i < first.probes().size(); i++) {
                DetectionStatus status = first.statuses().get(i);
                if (ProbeResultAggregator.needsConfirmation(status)) {
                    flagged.add(first.probes().get(i));
                }
            }
            if (!flagged.isEmpty() && scanConfiguration.doubleCheck()) {
                long confirmationStart = sessionStarts.getOrDefault(session.sessionId(), timeline.start());
                timeline.event("CONFIRMATION_START", confirmationStart, session.sessionId(), target.id(), trigger,
                        0, flagged.size(), null);
                runPass(session, target, flagged, scanConfiguration, ProbePass.CONFIRMATION, new ArrayList<>(), 0, second -> complete(session,
                        ProbeResultAggregator.aggregate(second.statuses()), "double-check complete", trigger, result));
            } else {
                complete(session, firstStatus, "probe batch complete", trigger, result);
            }
        });
        return session;
    }

    private void runPass(DetectionSession session, DetectionTarget target, List<ProbeDefinition> probes,
                         ClientDetectionConfig scanConfiguration,
                         ProbePass pass, List<DetectionStatus> statuses, int timeoutStreak,
                         Consumer<PassResult> complete) {
        if (!started || session.state().terminal()) return;
        int batchStart = statuses.size();
        if (batchStart >= probes.size()) {
            complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses)));
            return;
        }
        List<ProbeDefinition> batch = probes.subList(batchStart, Math.min(batchStart + 3, probes.size()));
        long batchTimeout = scanConfiguration.batchTimeoutTicks(timeoutStreak);
        ProbeRequest request = new ProbeRequest(session.sessionId(),
                new DetectionTarget(target.id(), target.name(), target.online(), target.platformHandle()), batch,
                batchStart / 3, trigger(session), batchTimeout);
        long scanStart = sessionStarts.getOrDefault(session.sessionId(), timeline.start());
        diagnostics.batchStarted(session.sessionId(), batchStart / 3,
                pass.name(), batch.stream().map(ProbeDefinition::id).toList());
        timeline.event("TRANSPORT_SEND", scanStart, request, "timeoutTicks=" + batchTimeout);
        AtomicReference<ProbeHandle> handleReference = new AtomicReference<>();
        AtomicBoolean responseClaimed = new AtomicBoolean();
        ProbeHandle handle;
        try {
            handle = transport.send(request, response -> {
            if (!responseClaimed.compareAndSet(false, true)) return;
            ProbeHandle callbackHandle = handleReference.get();
            if (callbackHandle != null) activeHandles.remove(session.targetId(), callbackHandle);
            if (!started || session.state().terminal()) return;
            try {
            ProbeResponse normalized = ProbeResponseNormalizer.normalize(request, response);
            if (normalized.outcome() == ProbeResponse.Outcome.SKIPPED
                    || normalized.outcome() == ProbeResponse.Outcome.CANCELLED) {
                while (statuses.size() < probes.size()) statuses.add(DetectionStatus.SKIPPED);
                complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses)));
                return;
            }
            if (normalized.outcome() != ProbeResponse.Outcome.RESPONSE) {
                DetectionStatus status = switch (normalized.outcome()) {
                    case TIMEOUT -> DetectionStatus.TIMEOUT;
                    case UNSUPPORTED -> DetectionStatus.UNSUPPORTED;
                    case DISCONNECTED, ERROR -> DetectionStatus.ERROR;
                    case CANCELLED, SKIPPED, RESPONSE -> throw new IllegalStateException("unreachable outcome");
                };
                for (int i = 0; i < batch.size(); i++) {
                    statuses.add(status);
                    addEvidence(session, batch.get(i), status, normalized.outcome(), pass, trigger(session),
                            ProbeEvidenceStrength.NONE, "transport " + normalized.outcome().name().toLowerCase(java.util.Locale.ROOT));
                }
                if (normalized.outcome() == ProbeResponse.Outcome.DISCONNECTED
                        || normalized.outcome() == ProbeResponse.Outcome.ERROR) {
                    while (statuses.size() < probes.size()) {
                        int missingIndex = statuses.size();
                        statuses.add(DetectionStatus.ERROR);
                        addEvidence(session, probes.get(missingIndex), DetectionStatus.ERROR,
                                normalized.outcome(), pass, trigger(session), ProbeEvidenceStrength.NONE,
                                "remaining probe omitted after terminal transport failure");
                    }
                    timeline.event("BATCH_COMPLETE", scanStart, request, "outcome=" + normalized.outcome());
                    complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses)));
                    return;
                }
            } else {
                String exploitLine = normalized.lines().get(3);
                boolean exploitPreventer = CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY.equals(exploitLine);
                for (int i = 0; i < batch.size(); i++) {
                    ProbeEvaluation evaluation = CheckHacksResponseEvaluator.evaluateDetailed(batch.get(i),
                            normalized.lines().get(i), normalized.componentIdentities().get(i), exploitPreventer);
                    statuses.add(evaluation.status());
                    timeline.event("PROBE_EVALUATION", scanStart, request,
                            "probe=" + batch.get(i).id() + " status=" + evaluation.status()
                                    + " evidence=" + evaluation.evidenceStrength());
                    addEvidence(session, batch.get(i), evaluation.status(), normalized.outcome(), pass,
                            trigger(session), evaluation.evidenceStrength(), evaluation.detail());
                }
            }
            // Silence is measured in whole timed-out batches: only a batch whose
            // every probe waited out the full deadline extends the streak. Any
            // response, unsupported batch, or terminal outcome resets it.
            int nextTimeoutStreak = normalized.outcome() == ProbeResponse.Outcome.TIMEOUT
                    ? timeoutStreak + 1 : 0;
            timeline.event("BATCH_COMPLETE", scanStart, request, "outcome=" + normalized.outcome());
            if (context != null && context.configuration().debug()) {
                context.logger().info("[ClientProbe] PASS trigger=" + trigger(session)
                        + " session=" + session.sessionId() + " pass=" + pass
                        + " batch=" + probeSummary(batch) + " outcome=" + normalized.outcome()
                        + " statuses=" + statuses.subList(batchStart,
                        Math.min(batchStart + batch.size(), statuses.size())));
            }
            if (statuses.size() >= probes.size()) {
                complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses)));
                return;
            }
            timeline.event("NEXT_BATCH_START", scanStart, request, null);
            Scheduler scheduler = context.platform().scheduler();
            Runnable nextBatch = () -> runPass(session, target, probes, scanConfiguration, pass, statuses,
                    nextTimeoutStreak, complete);
            if (scanConfiguration.betweenProbeTicks() == 0) nextBatch.run();
            else scheduler.runGlobalLater(nextBatch, scanConfiguration.betweenProbeTicks());
            } catch (RuntimeException exception) {
                if (context != null) context.logger().warning("[ClientProbe] response processing failed session="
                        + session.sessionId() + " batch=" + request.batchIndex() + " reason="
                        + exception.getClass().getSimpleName());
                complete(session, DetectionStatus.ERROR, "probe response processing failed", trigger(session),
                        terminalCallbacks.getOrDefault(session.sessionId(), ignored -> { }));
            }
            });
        } catch (RuntimeException exception) {
            if (!responseClaimed.compareAndSet(false, true)) return;
            if (context != null) context.logger().warning("[ClientProbe] transport send failed session="
                    + session.sessionId() + " batch=" + request.batchIndex() + " reason="
                    + exception.getClass().getSimpleName());
            while (statuses.size() < probes.size()) {
                int failedIndex = statuses.size();
                statuses.add(DetectionStatus.ERROR);
                addEvidence(session, probes.get(failedIndex), DetectionStatus.ERROR,
                        ProbeResponse.Outcome.ERROR, pass, trigger(session), ProbeEvidenceStrength.NONE,
                        "transport send failed");
            }
            complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses)));
            return;
        }
        handleReference.set(handle);
        if (activeSessions.get(session.targetId()) == session && !session.state().terminal()) {
            ProbeHandle existingHandle = activeHandles.putIfAbsent(session.targetId(), handle);
            if (existingHandle != null) handle.cancel();
        }
        if (session.state().terminal() && activeHandles.remove(session.targetId(), handle)) {
            handle.cancel();
        }
    }

    public void disconnect(UUID targetId) {
        ProbeHandle handle = activeHandles.remove(targetId);
        if (handle != null) handle.cancel();
        DetectionSession session = activeSessions.remove(targetId);
        if (session != null && !session.state().terminal()) {
            Consumer<DetectionResult> callback = terminalCallbacks.getOrDefault(session.sessionId(), ignored -> { });
            complete(session, DetectionStatus.SKIPPED, "player disconnected", trigger(session), callback);
        }
    }

    private static void addEvidence(DetectionSession session, ProbeDefinition probe, DetectionStatus status,                                    ProbeResponse.Outcome outcome, ProbePass pass, String trigger,
                                    ProbeEvidenceStrength evidenceStrength, String detail) {
        if (status == DetectionStatus.SKIPPED) return;
        EvidenceType evidenceType = status == DetectionStatus.ERROR || status == DetectionStatus.TIMEOUT
                ? EvidenceType.ERROR : status == DetectionStatus.UNSUPPORTED
                ? EvidenceType.UNSUPPORTED : EvidenceType.OBSERVATION;
        try {
            session.addEvidence(new Evidence(evidenceType,
                    ID, Instant.now(), java.util.Map.ofEntries(
                            java.util.Map.entry("session", session.sessionId().toString()),
                            java.util.Map.entry("probe", probe.id()),
                            java.util.Map.entry("displayName", probe.displayName()),
                            java.util.Map.entry("mode", probe.mode().name()),
                            java.util.Map.entry("transport", outcome.name()),
                            java.util.Map.entry("classification", status.name()),
                            java.util.Map.entry("evidenceStrength", evidenceStrength.name()),
                            java.util.Map.entry("verification", probe.verificationStatus().name()),
                            java.util.Map.entry("detail", detail),
                            java.util.Map.entry("pass", pass.name()),
                            java.util.Map.entry("trigger", trigger))));
        } catch (IllegalStateException ignored) {
            // A disconnect or timeout won the session race.
        }
    }

    private static String probeSummary(List<ProbeDefinition> probes) {
        return probes.stream()
                .map(probe -> probe.id() + "(" + probe.displayName() + ")")
                .toList()
                .toString();
    }

    private String trigger(DetectionSession session) {
        return sessionTrigger.getOrDefault(session.sessionId(), "MANUAL");
    }

    private final ConcurrentMap<UUID, String> sessionTrigger = new ConcurrentHashMap<>();

    private void complete(DetectionSession session, DetectionStatus status, String reason,
                          String trigger, Consumer<DetectionResult> result) {
        if (session.state().terminal()) return;
        try {
            session.beginCompletion();
        } catch (IllegalStateException ignored) {
            return;
        }
        DetectionResult value = DetectionResult.of(status, reason);
        try {
            session.complete(value);
        } catch (IllegalStateException ignored) {
            return;
        }
        diagnostics.scanCompleted(session.sessionId(), session.result(), System.nanoTime());
        activeSessions.remove(session.targetId(), session);
        sessionTrigger.remove(session.sessionId());
        long scanStart = sessionStarts.getOrDefault(session.sessionId(), timeline.start());
        boolean confirmed = session.result().evidence().stream()
                .anyMatch(item -> "CONFIRMATION".equals(item.metadata().get("pass")));
        if (confirmed) {
            timeline.event("CONFIRMATION_END", scanStart, session.sessionId(), session.targetId(), trigger,
                    0, session.result().evidence().size(), "status=" + status);
        }
        timeline.event("SCAN_COMPLETE", scanStart, session.sessionId(), session.targetId(), trigger,
                0, session.result().evidence().size(), "status=" + status);
        sessionStarts.remove(session.sessionId());
        if (context != null && context.configuration().debug() && status != DetectionStatus.SKIPPED) {
            completedScans.put(session.sessionId(), new CompletedScan(scanStart, session.targetId(), trigger));
            completedScanOrder.addLast(session.sessionId());
            trimCompletedScans();
        }
        if (context != null && context.configuration().debug()) {
            String probes = session.result().evidence().stream()
                    .filter(item -> "CONFIRMATION".equals(item.metadata().get("pass")))
                    .filter(item -> "DETECTED".equals(item.metadata().get("classification")))
                    .map(item -> item.metadata().get("probe"))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList()
                    .toString();
            context.logger().info("[ClientProbe] result session=" + session.sessionId()
                    + " status=" + status + " confirmationProbes=" + probes);
        }
        Consumer<DetectionResult> callback = terminalCallbacks.remove(session.sessionId());
        try {
            (callback == null ? result : callback).accept(session.result());
        } catch (RuntimeException exception) {
            if (context != null) context.logger().warning("[ClientProbe] terminal callback failed session="
                    + session.sessionId() + " reason=" + exception.getClass().getSimpleName());
        }
    }

    private void standaloneResult(UUID playerId, String playerName, String trigger, int probeCount,
                                  DetectionStatus status, String reason, Consumer<DetectionResult> callback) {
        if (playerId != null && playerName != null) {
            diagnostics.standaloneScan(playerId, playerName, trigger, probeCount, status);
        }
        try {
            callback.accept(DetectionResult.of(status, reason));
        } catch (RuntimeException exception) {
            if (context != null) context.logger().warning("[ClientProbe] standalone callback failed reason="
                    + exception.getClass().getSimpleName());
        }
    }

    public void enforcementComplete(DetectionResult result, String action) {
        if (context == null || !context.configuration().debug()) return;
        UUID sessionId = result.evidence().stream()
                .map(item -> item.metadata().get("session"))
                .filter(java.util.Objects::nonNull)
                .map(UUID::fromString)
                .findFirst().orElse(null);
        if (sessionId == null) return;
        CompletedScan completed = completedScans.remove(sessionId);
        if (completed == null) return;
        completedScanOrder.remove(sessionId);
        timeline.event("ENFORCEMENT_COMPLETE", completed.startedNanos(), sessionId, completed.targetId(),
                completed.trigger(), 0, result.evidence().size(), "action=" + action);
    }

    private void trimCompletedScans() {
        while (completedScans.size() > MAX_PENDING_TIMELINES) {
            UUID oldest = completedScanOrder.pollFirst();
            if (oldest == null) return;
            completedScans.remove(oldest);
        }
    }

    private record PassResult(List<ProbeDefinition> probes, List<DetectionStatus> statuses) { }

    @Override public void stop() {
        started = false;
        for (var entry : List.copyOf(activeSessions.entrySet())) {
            DetectionSession session = entry.getValue();
            ProbeHandle handle = activeHandles.remove(entry.getKey());
            if (handle != null) handle.cancel();
            Consumer<DetectionResult> callback = terminalCallbacks.getOrDefault(session.sessionId(), ignored -> { });
            complete(session, DetectionStatus.SKIPPED, "plugin shutdown", trigger(session), callback);
        }
        transport.stop();
        activeSessions.clear();
        activeHandles.clear();
        sessionTrigger.clear();
        sessionStarts.clear();
        terminalCallbacks.clear();
        completedScans.clear();
        completedScanOrder.clear();
        diagnostics.clearActive();
        diagnostics.clearHistory();
    }

    private record CompletedScan(long startedNanos, UUID targetId, String trigger) { }
}
