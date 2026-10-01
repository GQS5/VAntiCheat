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
    private final ClientProbeHealthService probeHealth = new ClientProbeHealthService();
    private volatile ClientSignalCollector signals;
    private volatile boolean passiveEnforcement = false;
    private DetectionModuleContext context;
    private volatile boolean started;
    private volatile ProbeTimeline timeline = new ProbeTimeline(null, false);
    private volatile Predicate<UUID> probeEligibility = ignored -> true;

    public CheckHacksClientDetectionModule(ClientDetectionConfig configuration, ClientProbeTransport transport) {
        this.configuration = configuration;
        this.transport = transport;
        this.signals = new ClientSignalCollector(configuration.passiveChannels());
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
        // Only probes whose declared transport is safe for unattended use are admitted. An
        // INTERACTIVE probe would open a client screen and capture the player's input, so it
        // never runs in the background unless the operator opted in explicitly.
        return startCheck(target, scanConfiguration.automaticEligibleProbes(), trigger, result, true,
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

    /** Automatic probes that passed the transport capability gate. */
    public List<ProbeDefinition> automaticEligibleProbes() {
        return configuration.automaticEligibleProbes();
    }

    public ClientProbeHealthService probeHealth() {
        return probeHealth;
    }

    public ClientSignalCollector signals() {
        return signals;
    }

    /**
     * Whether a detection supported only by passive identity evidence may reach
     * enforcement. Off by default: the proven passive channel belongs to a legitimate
     * utility mod, so auto-kicking on it would be a false positive.
     */
    public boolean passiveEnforcement() {
        return passiveEnforcement;
    }

    public void setPassiveEnforcement(boolean passiveEnforcement) {
        this.passiveEnforcement = passiveEnforcement;
    }

    /** Opens a fresh passive context for a connection. */
    public long beginPassiveContext(UUID playerId,
                                    site.vackstudio.vanticheat.platform.ClientPlatform platform, String brand) {
        return signals.beginConnection(playerId, platform, brand);
    }

    public ProbeRegistry registry() {
        return configuration.probeRegistry();
    }

    /** Atomically publishes a validated configuration for new sessions. */
    public void replaceConfiguration(ClientDetectionConfig replacement) {
        if (replacement == null) throw new IllegalArgumentException("replacement cannot be null");
        configuration = replacement;
        // A reload may change the declared channel set, so the collector's allow-list is
        // replaced too. Existing per-player context is left intact for the running session.
        signals = new ClientSignalCollector(replacement.passiveChannels());
    }

    public void setProbeEligibility(Predicate<UUID> probeEligibility) {
        this.probeEligibility = java.util.Objects.requireNonNull(probeEligibility, "probeEligibility");
    }

    /**
     * Supplies the last authoritative platform classification for probe-health records.
     * Health is informational, so a resolver that throws or is absent degrades to an
     * unknown platform rather than affecting detection.
     */
    public void setPlatformResolver(java.util.function.Function<UUID, site.vackstudio.vanticheat.platform.ClientPlatform> platformResolver) {
        this.platformResolver = platformResolver == null ? id -> null : platformResolver;
    }

    private volatile java.util.function.Function<UUID, site.vackstudio.vanticheat.platform.ClientPlatform> platformResolver =
            id -> null;

    private site.vackstudio.vanticheat.platform.ClientPlatform platformOf(UUID playerId) {
        try {
            return platformResolver.apply(playerId);
        } catch (RuntimeException exception) {
            return null;
        }
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
                .filter(probe -> probe.enabled())
                .filter(probe -> "JOIN".equals(trigger)
                        ? probe.automaticEligible(scanConfiguration.interactiveAutomatic())
                        : probe.manual())
                .distinct().toList();
        if (probes.isEmpty()) {
            String reason = "JOIN".equals(trigger)
                    ? (scanConfiguration.interactiveAutomatic()
                    ? "no automatic probes configured"
                    : "no passive automatic probes; interactive probes require /vac check or "
                    + "client-detection.auto-check.interactive")
                    : "no manual probes configured";
            standaloneResult(target.id(), target.name(), trigger, 0, DetectionStatus.SKIPPED, reason, result);
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
        diagnostics.selection(probes);
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
        // Passive probes are answered from already-observed protocol evidence and never
        // enter the transport, so they open no client UI and touch no world. Interactive
        // probes keep the existing bounded sign-editor path.
        List<ProbeDefinition> passive = probes.stream()
                .filter(ProbeDefinition::passiveChannelProbe).toList();
        List<ProbeDefinition> interactive = probes.stream()
                .filter(probe -> !probe.passiveChannelProbe()).toList();
        diagnostics.selection(probes);
        if (passive.isEmpty()) {
            runPass(session, target, interactive, scanConfiguration, ProbePass.INITIAL,
                    new ArrayList<>(), new ArrayList<>(), 0, first -> finishSession(session, target,
                            first, interactive, scanConfiguration, trigger, result));
            return session;
        }
        runPassive(session, target, passive, interactive, scanConfiguration, trigger, result);
        return session;
    }

    /** Evaluates passive probes from collected evidence, then continues with any interactive ones. */
    private void runPassive(DetectionSession session, DetectionTarget target,
                            List<ProbeDefinition> passive, List<ProbeDefinition> interactive,
                            ClientDetectionConfig scanConfiguration, String trigger,
                            Consumer<DetectionResult> result) {
        List<ProbeEvaluation> evaluations = new ArrayList<>();
        long scanStart = sessionStarts.getOrDefault(session.sessionId(), timeline.start());
        for (ProbeDefinition probe : passive) {
            boolean observed = signals.has(session.targetId(), probe.id());
            // Presence of the client's own payload on a mod-owned channel is authoritative
            // identity evidence. Its absence proves nothing and stays CLEAN.
            ProbeEvaluation evaluation = observed
                    ? new ProbeEvaluation(DetectionStatus.DETECTED, ProbeEvidenceStrength.STRONG,
                    "client sent the declared passive channel " + probe.passiveChannel())
                    : new ProbeEvaluation(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                    "declared passive channel was not observed");
            evaluations.add(evaluation);
            statusesFor(session, probe, evaluation, ProbePass.INITIAL, trigger, scanStart);
            // Passive evidence is event driven and stable, so re-reading it is a valid
            // confirmation. It is only recorded when the operator has opted in to enforcing
            // passive identity, so the default can never kick on a legitimate utility mod.
            if (observed && passiveEnforcement) {
                statusesFor(session, probe, evaluation, ProbePass.CONFIRMATION, trigger, scanStart);
            }
        }
        ProbeCorroboration.Decision passiveDecision = ProbeCorroboration.decide(
                ProbeCorroboration.observationsOf(passive, evaluations));
        lastDecision.put(session.sessionId(), passiveDecision);
        diagnostics.concluded(passiveDecision);
        if (passiveDecision.confirmed() && !interactive.isEmpty() && scanConfiguration.doubleCheck()) {
            // Confirm the passive identity against the interactive evidence where the
            // operator has both configured, instead of assuming the first signal.
            runPass(session, target, interactive, scanConfiguration, ProbePass.CONFIRMATION,
                    new ArrayList<>(), new ArrayList<>(), 0, second -> finishSession(session, target,
                            second, interactive, scanConfiguration, trigger, result));
            return;
        }
        if (interactive.isEmpty()) {
            complete(session, passiveDecision.status(), "passive scan complete: " + passiveDecision.reason(),
                    trigger, result);
            return;
        }
        runPass(session, target, interactive, scanConfiguration, ProbePass.INITIAL,
                new ArrayList<>(), new ArrayList<>(), 0, first -> finishSession(session, target,
                        first, interactive, scanConfiguration, trigger, result));
    }

    private void finishSession(DetectionSession session, DetectionTarget target, PassResult pass,
                               List<ProbeDefinition> probes, ClientDetectionConfig scanConfiguration,
                               String trigger, Consumer<DetectionResult> result) {
        ProbeCorroboration.Decision decision = decide(pass);
        lastDecision.put(session.sessionId(), decision);
        diagnostics.concluded(decision);
        List<ProbeDefinition> flagged = new ArrayList<>();
        for (int index = 0; index < pass.probes().size(); index++) {
            if (ProbeResultAggregator.needsConfirmation(pass.statuses().get(index))) {
                flagged.add(pass.probes().get(index));
            }
        }
        if (!flagged.isEmpty() && scanConfiguration.doubleCheck()) {
            runPass(session, target, flagged, scanConfiguration, ProbePass.CONFIRMATION,
                    new ArrayList<>(), new ArrayList<>(), 0, second -> {
                ProbeCorroboration.Decision secondDecision = decide(second);
                lastDecision.put(session.sessionId(), secondDecision);
                diagnostics.concluded(secondDecision);
                complete(session, secondDecision.status(),
                        "double-check complete: " + secondDecision.reason(), trigger, result);
            });
            return;
        }
        complete(session, decision.status(), "probe batch complete: " + decision.reason(), trigger, result);
    }

    private void statusesFor(DetectionSession session, ProbeDefinition probe, ProbeEvaluation evaluation,
                             ProbePass pass, String trigger, long scanStart) {
        diagnostics.evidence(evaluation.evidenceStrength());
        timeline.event("PASSIVE_EVALUATION", scanStart, session.sessionId(), session.targetId(), trigger,
                0, 1, "probe=" + probe.id() + " status=" + evaluation.status()
                        + " evidence=" + evaluation.evidenceStrength() + " channel=" + probe.passiveChannel()
                        + " pass=" + pass);
        addEvidence(session, probe, evaluation.status(), ProbeResponse.Outcome.RESPONSE,
                pass, trigger, evaluation.evidenceStrength(), evaluation.detail());
    }


    /** Bounded to active sessions: the entry is released on terminalization. */
    private final ConcurrentMap<UUID, ProbeCorroboration.Decision> lastDecision = new ConcurrentHashMap<>();

    public ProbeCorroboration.Decision lastDecision(UUID sessionId) {
        return sessionId == null ? null : lastDecision.get(sessionId);
    }

    /** Retained promotion decisions. Bounded to active sessions by construction. */
    public int retainedDecisionCount() {
        return lastDecision.size();
    }

    private void runPass(DetectionSession session, DetectionTarget target, List<ProbeDefinition> probes,
                         ClientDetectionConfig scanConfiguration,
                         ProbePass pass, List<DetectionStatus> statuses,
                         List<ProbeEvaluation> evaluations, int timeoutStreak,
                         Consumer<PassResult> complete) {
        if (!started || session.state().terminal()) return;
        int batchStart = statuses.size();
        if (batchStart >= probes.size()) {
            complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses),
                    List.copyOf(evaluations)));
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
                complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses),
                        List.copyOf(evaluations)));
                return;
            }
            if (normalized.outcome() != ProbeResponse.Outcome.RESPONSE) {
                diagnostics.transportOutcome();
                probeHealth.observeBatch(session.targetId(), target.name(),
                        platformOf(session.targetId()), batch.get(0).transport(), normalized.outcome(), -1L);
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
                    complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses),
                            List.copyOf(evaluations)));
                    return;
                }
            } else {
                String exploitLine = normalized.lines().get(3);
                boolean exploitPreventer = CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY.equals(exploitLine);
                for (int i = 0; i < batch.size(); i++) {
                    ProbeEvaluation evaluation = CheckHacksResponseEvaluator.evaluateDetailed(batch.get(i),
                            normalized.lines().get(i), normalized.componentIdentities().get(i), exploitPreventer);
                    statuses.add(evaluation.status());
                    evaluations.add(evaluation);
                    diagnostics.evidence(evaluation.evidenceStrength());
                    probeHealth.observeEvaluation(session.targetId(), target.name(),
                            platformOf(session.targetId()), evaluation, -1L);
                    timeline.event("PROBE_EVALUATION", scanStart, request,
                            "probe=" + batch.get(i).id() + " status=" + evaluation.status()
                                    + " evidence=" + evaluation.evidenceStrength()
                                    + " transport=" + batch.get(i).transport());
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
                complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses),
                        List.copyOf(evaluations)));
                return;
            }
            timeline.event("NEXT_BATCH_START", scanStart, request, null);
            Scheduler scheduler = context.platform().scheduler();
            Runnable nextBatch = () -> runPass(session, target, probes, scanConfiguration, pass, statuses,
                    evaluations, nextTimeoutStreak, complete);
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
            complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses),
                    List.copyOf(evaluations)));
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
        probeHealth.remove(targetId);
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
                            java.util.Map.entry("transport", probe.transport().name()),
                            java.util.Map.entry("outcome", outcome.name()),
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
        lastDecision.remove(session.sessionId());
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

    private record PassResult(List<ProbeDefinition> probes, List<DetectionStatus> statuses,
                              List<ProbeEvaluation> evaluations) {
        private PassResult(List<ProbeDefinition> probes, List<DetectionStatus> statuses) {
            this(probes, statuses, List.of());
        }
    }

    /**
     * Resolves a pass through the explicit promotion rules. This is the only place a scan
     * status is decided, and it can only return {@code DETECTED} when at least one
     * observation carried authoritative exact-identity evidence.
     */
    private ProbeCorroboration.Decision decide(PassResult pass) {
        if (pass.evaluations().isEmpty() || pass.evaluations().size() != pass.statuses().size()) {
            return new ProbeCorroboration.Decision(
                    conclusionFor(ProbeResultAggregator.aggregate(pass.statuses())),
                    ProbeResultAggregator.aggregate(pass.statuses()), List.of(),
                    "no per-probe evaluations available for this pass", 0, 0);
        }
        return ProbeCorroboration.decide(
                ProbeCorroboration.observationsOf(pass.probes(), pass.evaluations()));
    }

    private static ProbeCorroboration.Conclusion conclusionFor(DetectionStatus status) {
        return switch (status) {
            case DETECTED -> ProbeCorroboration.Conclusion.CONFIRMED;
            case UNCERTAIN -> ProbeCorroboration.Conclusion.AMBIGUOUS;
            case CLEAN -> ProbeCorroboration.Conclusion.NO_TARGET;
            case TIMEOUT -> ProbeCorroboration.Conclusion.TIMED_OUT;
            case UNSUPPORTED -> ProbeCorroboration.Conclusion.UNSUPPORTED;
            case ERROR -> ProbeCorroboration.Conclusion.FAILED;
            case SKIPPED -> ProbeCorroboration.Conclusion.NOT_RUN;
            case PROTECTED -> ProbeCorroboration.Conclusion.PROTECTED;
            case NOT_CHECKED, RUNNING -> ProbeCorroboration.Conclusion.NOT_RUN;
        };
    }

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
        lastDecision.clear();
        probeHealth.clear();
        diagnostics.clearActive();
        diagnostics.clearHistory();
    }

    private record CompletedScan(long startedNanos, UUID targetId, String trigger) { }
}
