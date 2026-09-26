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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckHacksClientDetectionModuleTest {
    @Test
    void timeoutIsProtectedAndProducesEvidenceForEveryProbe() {
        List<ProbeDefinition> probes = probes(4);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.TIMEOUT);
        CheckHacksClientDetectionModule module = module(probes, transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.PROTECTED, result.get().status());
        assertEquals(4, result.get().evidence().size());
        assertTrue(result.get().evidence().get(0).metadata().containsKey("session"));
    }

    @Test
    void disconnectStopsBatchProgressionAndCannotBecomeClean() {
        List<ProbeDefinition> probes = probes(4);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.DISCONNECTED);
        CheckHacksClientDetectionModule module = module(probes, transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.ERROR, result.get().status());
        assertEquals(1, transport.requests.size());
        assertEquals(3, result.get().evidence().size());
    }

    @Test
    void detectedProbeOnlyIsConfirmed() {
        List<ProbeDefinition> probes = probes(4);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        transport.confirmFirstProbe = true;
        CheckHacksClientDetectionModule module = module(probes, transport, true);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.CLEAN, result.get().status());
        assertEquals(3, transport.requests.size());
        assertEquals(5, result.get().evidence().size());
        assertEquals(1, transport.requests.get(2).probes().size());
        assertEquals("INITIAL", result.get().evidence().get(0).metadata().get("pass"));
        assertEquals("CONFIRMATION", result.get().evidence().get(4).metadata().get("pass"));
        assertEquals("probe-0", transport.requests.get(2).probes().get(0).id());
    }

    @Test
    void automaticTriggerIsRecordedAndCannotCompeteWithManualSession() {
        List<ProbeDefinition> probes = probes(1);
        BlockingTransport transport = new BlockingTransport();
        CheckHacksClientDetectionModule module = module(probes, transport, false);
        DetectionTarget target = target();
        AtomicReference<DetectionResult> manual = new AtomicReference<>();

        module.check(target, manual::set);
        assertTrue(module.isActive(target.id()));
        assertNull(module.checkIfIdle(target, probes, "JOIN", ignored -> { }));

        transport.respond();
        assertEquals(DetectionStatus.CLEAN, manual.get().status());
        assertEquals("MANUAL", manual.get().evidence().get(0).metadata().get("trigger"));
        assertTrue(!module.isActive(target.id()));
    }

    @Test
    void disconnectCancelsActiveSessionAndProbeHandle() {
        BlockingTransport transport = new BlockingTransport();
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        DetectionTarget target = target();

        module.check(target, ignored -> { });
        assertTrue(module.isActive(target.id()));

        module.disconnect(target.id());

        assertTrue(!module.isActive(target.id()));
        assertTrue(transport.cancelled);
    }

    private static CheckHacksClientDetectionModule module(List<ProbeDefinition> probes,
                                                           ClientProbeTransport transport, boolean doubleCheck) {
        ClientDetectionConfig config = new ClientDetectionConfig(true, doubleCheck, 20, 0, probes);
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new ImmediateScheduler()),
                FoundationConfig.defaults(), Logger.getLogger("test")));
        module.start();
        return module;
    }

    private static DetectionTarget target() {
        return new DetectionTarget(UUID.randomUUID(), "test", true, new Object());
    }

    private static List<ProbeDefinition> probes(int count) {
        List<ProbeDefinition> probes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            probes.add(new ProbeDefinition("probe-" + i, "Probe " + i, "key.probe." + i,
                    ProbeMode.METEOR, "", true, ProbeVerificationStatus.UNVERIFIED));
        }
        return probes;
    }

    private static final class FakeTransport implements ClientProbeTransport {
        private final ProbeResponse.Outcome outcome;
        private final List<ProbeRequest> requests = new ArrayList<>();
        private boolean confirmFirstProbe;

        private FakeTransport(ProbeResponse.Outcome outcome) { this.outcome = outcome; }

        @Override
        public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            requests.add(request);
            if (outcome != ProbeResponse.Outcome.RESPONSE) {
                callback.accept(new ProbeResponse(request.sessionId(), request.target().id(), List.of(), outcome));
            } else {
                List<String> lines = request.probes().stream()
                        .map(probe -> confirmFirstProbe && requests.size() == 1
                                && probe == request.probes().get(0)
                                ? probe.key() : probe.fallback())
                        .toList();
                callback.accept(new ProbeResponse(request.sessionId(), request.target().id(), lines, outcome));
            }
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }

        @Override public void stop() { }
    }

    private static final class BlockingTransport implements ClientProbeTransport {
        private ProbeRequest request;
        private java.util.function.Consumer<ProbeResponse> callback;
        private boolean cancelled;

        @Override
        public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            this.request = request;
            this.callback = callback;
            return new ProbeHandle() {
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }

        void respond() {
            callback.accept(new ProbeResponse(request.sessionId(), request.target().id(), List.of(""),
                    ProbeResponse.Outcome.RESPONSE));
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
