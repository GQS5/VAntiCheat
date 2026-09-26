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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class CheckHacksClientDetectionModule implements DetectionModule {
    public static final String ID = "client-detection.checkhacks";
    private final ClientDetectionConfig configuration;
    private final ClientProbeTransport transport;
    private final ConcurrentMap<UUID, DetectionSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProbeHandle> activeHandles = new ConcurrentHashMap<>();
    private DetectionModuleContext context;
    private boolean started;

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

    @Override public void initialize(DetectionModuleContext context) { this.context = context; }

    @Override public void start() { started = configuration.enabled(); }

    public DetectionSession check(DetectionTarget target, Consumer<DetectionResult> result) {
        return startCheck(target, configuration.probes(), "MANUAL", result, false);
    }

    public DetectionSession checkIfIdle(DetectionTarget target, List<ProbeDefinition> probes,
                                        String trigger, Consumer<DetectionResult> result) {
        return startCheck(target, probes, trigger, result, true);
    }

    public boolean isActive(UUID targetId) {
        return activeSessions.containsKey(targetId);
    }

    public String displayName(String probeId) {
        return configuration.probes().stream()
                .filter(probe -> probe.id().equals(probeId))
                .map(ProbeDefinition::displayName)
                .findFirst()
                .orElse(probeId);
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

    private DetectionSession startCheck(DetectionTarget target, List<ProbeDefinition> configuredProbes,
                                        String trigger, Consumer<DetectionResult> result, boolean onlyIfIdle) {
        if (!started) throw new IllegalStateException("Client detection is not started");
        DetectionSession existing = activeSessions.get(target.id());
        if (existing != null) {
            if (onlyIfIdle) return null;
            return existing;
        }
        DetectionSession session = new DetectionSession(UUID.randomUUID(), target.id(), ID, Instant.now());
        if (activeSessions.putIfAbsent(target.id(), session) != null) {
            return onlyIfIdle ? null : activeSessions.get(target.id());
        }
        sessionTrigger.put(session.sessionId(), trigger);
        session.start();
        List<ProbeDefinition> probes = configuredProbes.stream().filter(ProbeDefinition::enabled).toList();
        if (probes.isEmpty()) {
            complete(session, DetectionStatus.SKIPPED, "no probes configured", trigger, result);
            return session;
        }
        runPass(session, target, probes, ProbePass.INITIAL, new ArrayList<>(), first -> {
            DetectionStatus firstStatus = aggregate(first.statuses());
            List<ProbeDefinition> flagged = new ArrayList<>();
            for (int i = 0; i < first.probes().size(); i++) {
                DetectionStatus status = first.statuses().get(i);
                if (status == DetectionStatus.DETECTED || status == DetectionStatus.PROTECTED) {
                    flagged.add(first.probes().get(i));
                }
            }
            if (!flagged.isEmpty() && configuration.doubleCheck()) {
                runPass(session, target, flagged, ProbePass.CONFIRMATION, new ArrayList<>(), second -> complete(session,
                        aggregate(second.statuses()), "double-check complete", trigger, result));
            } else {
                complete(session, firstStatus, "probe batch complete", trigger, result);
            }
        });
        return session;
    }

    private void runPass(DetectionSession session, DetectionTarget target, List<ProbeDefinition> probes,
                         ProbePass pass, List<DetectionStatus> statuses, Consumer<PassResult> complete) {
        if (!started || session.state().terminal()) return;
        int batchStart = statuses.size();
        if (batchStart >= probes.size()) {
            complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses)));
            return;
        }
        List<ProbeDefinition> batch = probes.subList(batchStart, Math.min(batchStart + 3, probes.size()));
        ProbeRequest request = new ProbeRequest(session.sessionId(),
                new DetectionTarget(target.id(), target.name(), target.online(), target.platformHandle()), batch);
        ProbeHandle handle = transport.send(request, response -> {
            activeHandles.remove(session.targetId());
            if (session.state().terminal()) return;
            if (response.outcome() != ProbeResponse.Outcome.RESPONSE) {
                DetectionStatus status = response.outcome() == ProbeResponse.Outcome.TIMEOUT
                        ? DetectionStatus.PROTECTED : DetectionStatus.ERROR;
                for (int i = 0; i < batch.size(); i++) {
                    statuses.add(status);
                    addEvidence(session, batch.get(i), status, response.outcome(), pass, trigger(session));
                }
                if (response.outcome() == ProbeResponse.Outcome.DISCONNECTED
                        || response.outcome() == ProbeResponse.Outcome.ERROR) {
                    while (statuses.size() < probes.size()) statuses.add(DetectionStatus.ERROR);
                    complete.accept(new PassResult(List.copyOf(probes), List.copyOf(statuses)));
                    return;
                }
            } else {
                String exploitLine = response.lines().size() > 3 ? response.lines().get(3) : "";
                boolean exploitPreventer = CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY.equalsIgnoreCase(exploitLine);
                for (int i = 0; i < batch.size(); i++) {
                    String line = response.lines().size() > i ? response.lines().get(i) : "";
                    DetectionStatus status = CheckHacksResponseEvaluator.evaluate(batch.get(i), line,
                            exploitPreventer, response.outcome());
                    statuses.add(status);
                    addEvidence(session, batch.get(i), status, response.outcome(), pass, trigger(session));
                }
            }
            Scheduler scheduler = context.platform().scheduler();
            scheduler.runGlobalLater(() -> runPass(session, target, probes, pass, statuses, complete),
                    configuration.betweenProbeTicks());
        });
        activeHandles.put(session.targetId(), handle);
        if (session.state().terminal() && activeHandles.remove(session.targetId(), handle)) {
            handle.cancel();
        }
    }

    public void disconnect(UUID targetId) {
        ProbeHandle handle = activeHandles.remove(targetId);
        if (handle != null) handle.cancel();
        DetectionSession session = activeSessions.remove(targetId);
        if (session != null && !session.state().terminal()) {
            session.cancel("player disconnected");
            sessionTrigger.remove(session.sessionId());
        }
    }

    private static void addEvidence(DetectionSession session, ProbeDefinition probe, DetectionStatus status,
                                     ProbeResponse.Outcome outcome, ProbePass pass, String trigger) {
        session.addEvidence(new Evidence(status == DetectionStatus.ERROR ? EvidenceType.ERROR : EvidenceType.OBSERVATION,
                ID, Instant.now(), java.util.Map.of(
                        "session", session.sessionId().toString(),
                        "probe", probe.id(),
                        "mode", probe.mode().name(),
                         "transport", outcome.name(),
                         "classification", status.name(),
                         "pass", pass.name(),
                         "trigger", trigger)));
    }

    private String trigger(DetectionSession session) {
        return sessionTrigger.getOrDefault(session.sessionId(), "MANUAL");
    }

    private final ConcurrentMap<UUID, String> sessionTrigger = new ConcurrentHashMap<>();

    private void complete(DetectionSession session, DetectionStatus status, String reason,
                          String trigger, Consumer<DetectionResult> result) {
        if (session.state().terminal()) return;
        session.beginCompletion();
        DetectionResult value = DetectionResult.of(status, reason);
        session.complete(value);
        activeSessions.remove(session.targetId(), session);
        sessionTrigger.remove(session.sessionId());
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
        result.accept(session.result());
    }

    private static DetectionStatus aggregate(List<DetectionStatus> statuses) {
        if (statuses.contains(DetectionStatus.DETECTED)) return DetectionStatus.DETECTED;
        if (statuses.contains(DetectionStatus.PROTECTED)) return DetectionStatus.PROTECTED;
        if (statuses.contains(DetectionStatus.ERROR)) return DetectionStatus.ERROR;
        return DetectionStatus.CLEAN;
    }

    private record PassResult(List<ProbeDefinition> probes, List<DetectionStatus> statuses) { }

    @Override public void stop() {
        started = false;
        transport.stop();
        activeSessions.clear();
        activeHandles.clear();
        sessionTrigger.clear();
    }
}
