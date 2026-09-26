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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisabledProbeExclusionTest {
    @Test
    void disabledProbeListedForAutoCheckIsNeverSelected() {
        ProbeDefinition enabled = new ProbeDefinition("enabled-probe", "Enabled", "key.enabled",
                ProbeMode.TRANSLATE, "", true, ProbeVerificationStatus.UNVERIFIED);
        ProbeDefinition disabled = new ProbeDefinition("disabled-probe", "Disabled", "key.disabled",
                ProbeMode.TRANSLATE, "", false, ProbeVerificationStatus.UNVERIFIED);
        ClientDetectionConfig config = new ClientDetectionConfig(true, true, 20, 0,
                List.of(enabled, disabled), true, 1, false, List.of("enabled-probe", "disabled-probe"), 32);

        assertEquals(List.of(enabled), config.automaticProbes());
    }

    @Test
    void checkWithOnlyDisabledProbesEndsSkippedWithoutTransport() {
        ProbeDefinition disabled = new ProbeDefinition("disabled-probe", "Disabled", "key.disabled",
                ProbeMode.TRANSLATE, "", false, ProbeVerificationStatus.UNVERIFIED);
        ClientDetectionConfig config = new ClientDetectionConfig(true, true, 20, 0, List.of(disabled));
        CountingTransport transport = new CountingTransport();
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new ImmediateScheduler()),
                FoundationConfig.defaults(), Logger.getAnonymousLogger()));
        module.start();
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(new DetectionTarget(UUID.randomUUID(), "player", true, new Object()), result::set);

        assertEquals(DetectionStatus.SKIPPED, result.get().status());
        assertEquals(0, transport.sends);
        assertTrue(result.get().evidence().isEmpty());
    }

    private static final class CountingTransport implements ClientProbeTransport {
        private int sends;
        @Override public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            sends++;
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
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
