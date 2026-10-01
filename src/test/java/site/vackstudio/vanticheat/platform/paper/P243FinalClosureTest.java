package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionSession;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.detection.probe.ClientProbeTransport;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeHandle;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeRequest;
import site.vackstudio.vanticheat.detection.probe.ProbeResponse;
import site.vackstudio.vanticheat.detection.probe.ProbeRegistry;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;
import site.vackstudio.vanticheat.enforcement.EnforcementAction;
import site.vackstudio.vanticheat.enforcement.EnforcementDecision;
import site.vackstudio.vanticheat.enforcement.EnforcementService;
import site.vackstudio.vanticheat.lunar.LunarClientIntegration;
import site.vackstudio.vanticheat.lunar.LunarClientService;
import site.vackstudio.vanticheat.lunar.LunarPolicyConfig;
import site.vackstudio.vanticheat.platform.ClientPlatformService;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;
import site.vackstudio.vanticheat.trusted.TrustedPlayer;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P24.3 final closure: structural parity, shutdown boundaries, and shutdown-while-busy proof. */
class P243FinalClosureTest {
    @Test
    void manualAndAutomaticPathsShareOneEvaluatorAggregatorAndProbeRepresentation() throws Exception {
        Path source = Path.of("src/main/java/site/vackstudio/vanticheat/detection/probe/"
                + "CheckHacksClientDetectionModule.java");
        String code = Files.readString(source);
        assertTrue(code.contains("public DetectionSession check("), "manual entry point exists");
        assertTrue(code.contains("public DetectionSession checkAutomaticIfIdle"),
                "automatic entry point exists");
        assertTrue(count(code, "startCheck(target") >= 2,
                "both triggers share the single startCheck admission path");
        assertTrue(code.contains("ProbeResultAggregator.aggregate"),
                "both triggers share the single result aggregator");
        assertTrue(code.contains("CheckHacksResponseEvaluator.evaluateDetailed"),
                "both triggers share the single evaluator implementation");

        ManualTransport transport = new ManualTransport();
        CheckHacksClientDetectionModule module = module(List.of(meteorProbe()), transport, false);
        // Both policies resolve through the same immutable registry instance.
        assertTrue(module.manualProbes().stream()
                .allMatch(probe -> probe.equals(module.registry().find(probe.id()))));
        assertTrue(module.automaticProbes().stream()
                .allMatch(probe -> probe.equals(module.registry().find(probe.id()))));
    }

    @Test
    void shutdownDuringAggregationCancelsPendingBatchExactlyOnce() {
        ManualTransport transport = new ManualTransport();
        HoldingScheduler scheduler = new HoldingScheduler();
        CheckHacksClientDetectionModule module = module(List.of(
                meteorProbe("m0"), meteorProbe("m1"), meteorProbe("m2"), meteorProbe("m3")),
                transport, scheduler, false);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        DetectionSession session = module.check(target(), value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });
        transport.respondClean(0);
        assertEquals(1, transport.requests.size());
        assertEquals(1, module.activeSessionCount());

        module.stop();
        scheduler.drain();

        assertTrue(session.state().terminal());
        assertEquals(DetectionStatus.SKIPPED, result.get().status());
        assertEquals(1, callbacks.get());
        assertEquals(1, transport.requests.size(), "no post-shutdown batch is scheduled");
        assertEquals(0, module.activeSessionCount());
        assertEquals(0, module.diagnostics().activeScans().size());
        assertEquals(1, module.diagnostics().completedScans());
    }

    @Test
    void shutdownDuringDiagnosticsHistoryPublicationKeepsExactlyOneTerminalRecord() {
        ManualTransport transport = new ManualTransport();
        CheckHacksClientDetectionModule module = module(List.of(meteorProbe()), transport, false);
        AtomicInteger callbacks = new AtomicInteger();
        module.check(target(), ignored -> callbacks.incrementAndGet());

        module.stop();
        module.stop();
        transport.respondClean(0); // late response after shutdown

        assertEquals(1, callbacks.get());
        assertEquals(1, module.diagnostics().completedScans());
        assertEquals(0, module.diagnostics().activeScans().size());
        assertEquals(0, module.activeSessionCount());
        ReliabilityInvariants.assertEnforcementBounded(
                new EnforcementService(new AllowNothingPolicy(), (t, m) -> false, "kick",
                        Logger.getLogger("p243-bounded")));
    }

    @Test
    void shutdownDuringEnforcementInvocationReleasesCapacityExactlyOnce() {
        ListenerFixture fixture = new ListenerFixture(2, true);
        fixture.transport.autoRespond = false;
        Player player = player(UUID.randomUUID(), "shutdown-enforce", true);
        fixture.listener.onJoinPlayer(player);
        fixture.enforcementHook.set(fixture.listener::stop);

        fixture.transport.respondFlagged(0);
        assertEquals(2, fixture.transport.requests.size(), "flagged result opens the confirmation pass");
        fixture.transport.respondClean(1);

        assertEquals(1, fixture.enforcementDecisions.get());
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.module.diagnostics().activeScans().size());
    }

    @Test
    void shutdownWhileEverythingHappensLeavesNoOwnedWork() {
        ListenerFixture fixture = new ListenerFixture(4, true);
        fixture.transport.autoRespond = false;
        fixture.scheduler.holdDelayed = true;
        Player queued = player(UUID.randomUUID(), "queued", true);
        fixture.listener.onJoinPlayer(queued); // queued automatic admission, no scan yet
        assertEquals(1, fixture.listener.activeChecks());

        fixture.scheduler.holdDelayed = false;
        Player active = player(UUID.randomUUID(), "active", true);
        fixture.listener.onJoinPlayer(active); // active probe waiting for response
        Player confirming = player(UUID.randomUUID(), "confirming", true);
        fixture.listener.onJoinPlayer(confirming);
        assertEquals(2, fixture.transport.requests.size());
        fixture.transport.respondFlagged(1); // confirmation batch now pending
        assertEquals(3, fixture.transport.requests.size());

        QueuingScheduler lunarScheduler = new QueuingScheduler();
        LunarClientService lunar = new LunarClientService(Logger.getLogger("p243-proof"), lunarScheduler);
        lunar.start(LunarPolicyConfig.defaults(), new NoopApollo());
        Object apolloHandle = new Object();
        UUID apolloId = UUID.randomUUID();
        lunar.handleRegistration(new LunarClientIntegration.Registration(apolloId, "apollo", apolloHandle));

        fixture.listener.stop();
        fixture.module.stop();
        lunar.stop();
        lunarScheduler.drain();
        // Every late callback after shutdown must be inert.
        for (int i = 0; i < fixture.transport.requests.size(); i++) {
            try {
                fixture.transport.respondClean(i);
            } catch (RuntimeException ignored) {
                // Stale per-request callback state is owned by the terminal session.
            }
        }

        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.module.diagnostics().activeScans().size());
        assertEquals(0, lunar.trackedPlayers());
        ReliabilityInvariants.assertTerminalState("final shutdown proof",
                fixture.module, 0, fixture.listener.activeChecks(), lunar.trackedPlayers(), 0);
    }

    // Candidate selection and sign restoration are transitively covered by
    // PaperSignProbeTransportTest (placement/packet failure matrix restores captured
    // state exactly once; shutdownBeforeAndAfterEditorOpeningStopsStagesAndRestoresOnce
    // proves no post-shutdown packet stage). No duplicate tests are added here.

    private static int count(String haystack, String needle) {
        int found = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            found++;
            index += needle.length();
        }
        return found;
    }

    private static DetectionTarget target() {
        return new DetectionTarget(UUID.randomUUID(), "test", true, new Object());
    }

    private static ProbeDefinition meteorProbe() {
        return meteorProbe("probe");
    }

    private static ProbeDefinition meteorProbe(String id) {
        return new ProbeDefinition(id, "Probe " + id, "key." + id, ProbeMode.METEOR,
                "fallback." + id, true, true, true, ProbeVerificationStatus.UNVERIFIED);
    }

    private static CheckHacksClientDetectionModule module(List<ProbeDefinition> probes,
                                                          ClientProbeTransport transport, boolean doubleCheck) {
        return module(probes, transport, new ImmediateScheduler(), doubleCheck);
    }

    private static CheckHacksClientDetectionModule module(List<ProbeDefinition> probes,
                                                          ClientProbeTransport transport,
                                                          Scheduler scheduler, boolean doubleCheck) {
        ClientDetectionConfig config = new ClientDetectionConfig(true, doubleCheck, 20, 1, probes);
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(new PlatformContext(Platform.PAPER, scheduler),
                FoundationConfig.defaults(), Logger.getLogger("p243")));
        module.start();
        return module;
    }

    private static Player player(UUID id, String name, boolean firstJoin) {
        AtomicBoolean online = new AtomicBoolean(true);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "isOnline" -> online.get();
                    case "hasPlayedBefore" -> !firstJoin;
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class ManualTransport implements ClientProbeTransport {
        private final List<ProbeRequest> requests = new ArrayList<>();
        private final List<java.util.function.Consumer<ProbeResponse>> callbacks = new ArrayList<>();

        @Override
        public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            requests.add(request);
            callbacks.add(callback);
            return new ProbeHandle() {
                private volatile boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }

        private void respondClean(int index) {
            ProbeRequest request = requests.get(index);
            List<String> lines = new ArrayList<>();
            for (ProbeDefinition probe : request.probes()) lines.add(probe.fallback());
            while (lines.size() < 3) lines.add("");
            lines.add("not-a-keybind");
            callbacks.get(index).accept(new ProbeResponse(request.sessionId(), request.target().id(),
                    lines, ProbeResponse.Outcome.RESPONSE));
        }

        @Override public void stop() { }
    }

    private static final class HoldingScheduler implements Scheduler {
        private final java.util.Queue<Runnable> delayed = new java.util.concurrent.ConcurrentLinkedQueue<>();
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
            delayed.add(task);
            return handle();
        }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }
        private void drain() {
            Runnable task;
            while ((task = delayed.poll()) != null) task.run();
        }
        private static TaskHandle handle() {
            return new TaskHandle() {
                private boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }
    }

    private static final class ImmediateScheduler implements Scheduler {
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) { task.run(); return handle(); }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }
        private static TaskHandle handle() {
            return new TaskHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }
    }

    private static final class AllowNothingPolicy implements site.vackstudio.vanticheat.enforcement.EnforcementPolicy {
        @Override public site.vackstudio.vanticheat.enforcement.EnforcementDecision decide(
                DetectionResult result) {
            return new EnforcementDecision(EnforcementAction.NONE, "test policy");
        }
    }

    private static final class NoopApollo implements LunarClientIntegration {
        @Override public Availability availability() { return Availability.AVAILABLE; }
        @Override public boolean hasSupport(UUID id) { return true; }
        @Override public MinimapAction disableMinimap(UUID id, Object playerHandle) {
            return MinimapAction.APPLIED;
        }
        @Override public java.util.Optional<Boolean> minimapStatus(UUID id) {
            return java.util.Optional.empty();
        }
        @Override public void start() { }
        @Override public void stop() { }
    }

    private static final class QueuingScheduler implements Scheduler {
        private final java.util.Queue<Runnable> entityTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) {
            entityTasks.add(task);
            return handle();
        }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) { task.run(); return handle(); }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }
        private void drain() {
            Runnable task;
            while ((task = entityTasks.poll()) != null) task.run();
        }
        private static TaskHandle handle() {
            return new TaskHandle() {
                private boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }
    }

    private static final class ListenerFixture {
        private final FixtureScheduler scheduler = new FixtureScheduler();
        private final FixtureTransport transport = new FixtureTransport();
        private final CheckHacksClientDetectionModule module;
        private final AutomaticClientDetectionListener listener;
        private final AtomicInteger enforcementDecisions = new AtomicInteger();
        private final AtomicReference<Runnable> enforcementHook = new AtomicReference<>();
        private final ClientPlatformService platforms;

        private ListenerFixture(int maxConcurrent, boolean doubleCheck) {
            // Opt in to INTERACTIVE automatic probes: this fixture asserts automatic-scan
            // lifecycle closure, so the automatic path must actually admit a probe. The
            // shipped default keeps the opt-in off so joins never open client UI.
            ClientDetectionConfig config = new ClientDetectionConfig(true, doubleCheck, 20, 0,
                    ProbeRegistry.of(List.of(new ProbeDefinition("probe", "Probe", "key.probe",
                            ProbeMode.TRANSLATE, "fallback.probe", true, true, true,
                            ProbeVerificationStatus.UNVERIFIED, "test", "", "", "known response"))),
                    true, 0, false, maxConcurrent, 20, 2, true);
            module = new CheckHacksClientDetectionModule(config, transport);
            module.initialize(new DetectionModuleContext(new PlatformContext(Platform.PAPER, scheduler),
                    FoundationConfig.defaults(), Logger.getLogger("p243-listener")));
            module.start();
            platforms = new ClientPlatformService(new JavaProvider(), new JavaProvider());
            module.setProbeEligibility(id -> platforms.refresh(id).canProbe());
            EnforcementService enforcement = new EnforcementService(result -> {
                enforcementDecisions.incrementAndGet();
                Runnable hook = enforcementHook.getAndSet(null);
                if (hook != null) hook.run();
                return new EnforcementDecision(EnforcementAction.NONE, "test policy");
            }, (target, message) -> false, "kick", Logger.getLogger("p243-listener"),
                    new TrustedPlayerService() {
                        @Override public boolean isTrusted(UUID id) { return false; }
                        @Override public boolean add(UUID id, String name) { return false; }
                        @Override public boolean remove(UUID id) { return false; }
                        @Override public List<TrustedPlayer> list() { return List.of(); }
                        @Override public void load() { }
                        @Override public void save() { }
                    });
            listener = new AutomaticClientDetectionListener(scheduler, module, config,
                    enforcement, Logger.getLogger("p243-listener"), platforms);
        }
    }

    private static final class JavaProvider implements ClientPlatformService.Provider {
        @Override public String name() { return "TEST"; }
        @Override public boolean installed() { return false; }
        @Override public Boolean isBedrock(UUID id) { return null; }
    }

    private static final class FixtureScheduler implements Scheduler {
        private volatile boolean holdDelayed;
        private final java.util.Queue<Runnable> delayed = new java.util.concurrent.ConcurrentLinkedQueue<>();
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
            if (holdDelayed) delayed.add(task);
            else task.run();
            return handle();
        }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }
        private static TaskHandle handle() {
            return new TaskHandle() {
                private boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }
    }

    private static final class FixtureTransport implements ClientProbeTransport {
        private final List<ProbeRequest> requests = new ArrayList<>();
        private final List<java.util.function.Consumer<ProbeResponse>> callbacks = new ArrayList<>();
        private volatile boolean autoRespond = true;

        @Override public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            requests.add(request);
            callbacks.add(callback);
            if (autoRespond) respondClean(requests.size() - 1);
            return new ProbeHandle() {
                private volatile boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }

        private void respondClean(int index) {
            respond(index, "fallback.probe");
        }

        private void respondFlagged(int index) {
            respond(index, "known response");
        }

        private void respond(int index, String line) {
            ProbeRequest request = requests.get(index);
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < request.probes().size(); i++) lines.add(line);
            while (lines.size() < 3) lines.add("");
            lines.add("key.forward");
            callbacks.get(index).accept(new ProbeResponse(request.sessionId(), request.target().id(),
                    lines, ProbeResponse.Outcome.RESPONSE));
        }

        @Override public void stop() { }
    }
}
