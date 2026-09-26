package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreePlayerIsolationTest {
    @Test
    void threeSimultaneousSessionsCompleteIndependentlyWithoutSharedState() {
        ProbeDefinition probe = new ProbeDefinition("probe-0", "Probe 0", "key.probe.0",
                ProbeMode.METEOR, "", true, ProbeVerificationStatus.UNVERIFIED);
        ClientDetectionConfig config = new ClientDetectionConfig(true, false, 20, 0, List.of(probe));
        MultiBlockingTransport transport = new MultiBlockingTransport();
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new ImmediateScheduler()),
                FoundationConfig.defaults(), Logger.getAnonymousLogger()));
        module.start();

        DetectionTarget playerA = target("PlayerA");
        DetectionTarget playerB = target("PlayerB");
        DetectionTarget playerC = target("PlayerC");
        AtomicReference<DetectionResult> resultA = new AtomicReference<>();
        AtomicReference<DetectionResult> resultB = new AtomicReference<>();
        AtomicReference<DetectionResult> resultC = new AtomicReference<>();

        module.check(playerA, resultA::set);
        module.check(playerB, resultB::set);
        module.check(playerC, resultC::set);

        assertTrue(module.isActive(playerA.id()));
        assertTrue(module.isActive(playerB.id()));
        assertTrue(module.isActive(playerC.id()));

        transport.respond(playerB.id(), List.of(""), ProbeResponse.Outcome.RESPONSE);
        transport.respond(playerA.id(), List.of(""), ProbeResponse.Outcome.RESPONSE);
        transport.respond(playerC.id(), List.of(""), ProbeResponse.Outcome.RESPONSE);

        for (DetectionResult result : List.of(resultA.get(), resultB.get(), resultC.get())) {
            assertTrue(result.status() == DetectionStatus.CLEAN
                    || result.status() == DetectionStatus.DETECTED
                    || result.status() == DetectionStatus.PROTECTED
                    || result.status() == DetectionStatus.ERROR
                    || result.status() == DetectionStatus.SKIPPED
                    || result.status() == DetectionStatus.UNCERTAIN);
            assertNotEquals(DetectionStatus.RUNNING, result.status());
            assertNotEquals(DetectionStatus.NOT_CHECKED, result.status());
        }
        String sessionA = evidenceSession(resultA.get());
        String sessionB = evidenceSession(resultB.get());
        String sessionC = evidenceSession(resultC.get());
        assertNotEquals("unknown", sessionA);
        assertNotEquals("unknown", sessionB);
        assertNotEquals("unknown", sessionC);
        assertNotEquals(sessionA, sessionB);
        assertNotEquals(sessionA, sessionC);
        assertNotEquals(sessionB, sessionC);
        assertEquals(transport.sessionFor(playerA.id()), sessionA);
        assertEquals(transport.sessionFor(playerB.id()), sessionB);
        assertEquals(transport.sessionFor(playerC.id()), sessionC);
        assertTrue(!module.isActive(playerA.id()));
        assertTrue(!module.isActive(playerB.id()));
        assertTrue(!module.isActive(playerC.id()));
    }

    private static DetectionTarget target(String name) {
        return new DetectionTarget(UUID.randomUUID(), name, true, new Object());
    }

    private static String evidenceSession(DetectionResult result) {
        return result.evidence().stream()
                .map(item -> item.metadata().get("session"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("unknown");
    }

    private static final class MultiBlockingTransport implements ClientProbeTransport {
        private final Map<UUID, ProbeRequest> requests = new ConcurrentHashMap<>();
        private final Map<UUID, java.util.function.Consumer<ProbeResponse>> callbacks = new ConcurrentHashMap<>();

        @Override public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            requests.put(request.target().id(), request);
            callbacks.put(request.target().id(), callback);
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }

        void respond(UUID targetId, List<String> lines, ProbeResponse.Outcome outcome) {
            ProbeRequest request = requests.get(targetId);
            java.util.function.Consumer<ProbeResponse> callback = callbacks.remove(targetId);
            callback.accept(new ProbeResponse(request.sessionId(), request.target().id(), lines, outcome));
        }

        String sessionFor(UUID targetId) {
            ProbeRequest request = requests.get(targetId);
            return request == null ? "unknown" : request.sessionId().toString();
        }

        @Override public void stop() { }
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
}
