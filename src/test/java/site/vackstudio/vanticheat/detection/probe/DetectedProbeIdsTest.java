package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.Evidence;
import site.vackstudio.vanticheat.detection.EvidenceType;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.Scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectedProbeIdsTest {
    @Test
    void confirmationDetectionsTakePrecedenceOverInitial() {
        DetectionResult result = DetectionResult.of(DetectionStatus.DETECTED, "done")
                .withEvidence(evidence("INITIAL", "DETECTED", "probe-a", "s1"))
                .withEvidence(evidence("CONFIRMATION", "DETECTED", "probe-b", "s1"));

        assertEquals(List.of("probe-b"), CheckHacksClientDetectionModule.detectedProbeIds(result));
    }

    @Test
    void fallsBackToInitialDetectionWithoutConfirmation() {
        DetectionResult result = DetectionResult.of(DetectionStatus.DETECTED, "done")
                .withEvidence(evidence("INITIAL", "DETECTED", "probe-a", "s1"));

        assertEquals(List.of("probe-a"), CheckHacksClientDetectionModule.detectedProbeIds(result));
    }

    @Test
    void cleanResultYieldsNoDetectedProbes() {
        DetectionResult result = DetectionResult.of(DetectionStatus.CLEAN, "done")
                .withEvidence(evidence("INITIAL", "CLEAN", "probe-a", "s1"));

        assertTrue(CheckHacksClientDetectionModule.detectedProbeIds(result).isEmpty());
    }

    @Test
    void displayNameFallsBackToProbeIdWhenUnknown() {
        ClientDetectionConfig config = new ClientDetectionConfig(true, true, 20, 0,
                List.of(new ProbeDefinition("known", "Known Display", "key.known",
                        ProbeMode.TRANSLATE, "", true, ProbeVerificationStatus.UNVERIFIED)));
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config,
                new NoopTransport());
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new NoopScheduler()),
                FoundationConfig.defaults(), Logger.getAnonymousLogger()));

        assertEquals("Known Display", module.displayName("known"));
        assertEquals("missing", module.displayName("missing"));
    }

    private static Evidence evidence(String pass, String classification, String probe, String session) {
        return new Evidence(EvidenceType.OBSERVATION, "test", Instant.EPOCH,
                Map.of("pass", pass, "classification", classification, "probe", probe, "session", session));
    }

    private static final class NoopTransport implements ClientProbeTransport {
        @Override public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }
        @Override public void stop() { }
    }

    private static final class NoopScheduler implements Scheduler {
        @Override public site.vackstudio.vanticheat.platform.TaskHandle runAtEntity(
                site.vackstudio.vanticheat.platform.EntityTarget target, Runnable task) { return handle(); }
        @Override public site.vackstudio.vanticheat.platform.TaskHandle runAtLocation(
                site.vackstudio.vanticheat.platform.RegionTarget target, Runnable task) { return handle(); }
        @Override public site.vackstudio.vanticheat.platform.TaskHandle runGlobal(Runnable task) { return handle(); }
        @Override public site.vackstudio.vanticheat.platform.TaskHandle runGlobalLater(Runnable task, long delayTicks) { return handle(); }
        @Override public site.vackstudio.vanticheat.platform.TaskHandle runAsync(Runnable task) { return handle(); }
        @Override public void shutdown() { }
        private static site.vackstudio.vanticheat.platform.TaskHandle handle() {
            return new site.vackstudio.vanticheat.platform.TaskHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }
    }
}
