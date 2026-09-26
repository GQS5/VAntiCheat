package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDetectionConfigTest {
    @Test
    void bundledDefinitionsParseWithVerificationMetadata() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            ClientDetectionConfig config = ClientDetectionConfig.parse(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(config.enabled());
            assertEquals(40, config.timeoutTicks());
            assertEquals(1, config.betweenProbeTicks());
            assertEquals(28, config.probes().size());
            assertTrue(config.autoCheckOnJoin());
            assertEquals(1, config.autoCheckDelayTicks());
            assertEquals(32, config.maxConcurrentAutoChecks());
            assertEquals(27, config.automaticProbes().size());
            assertTrue(config.probes().stream()
                    .filter(probe -> probe.id().equals("xaeros-minimap"))
                    .noneMatch(probe -> probe.enabled()));
            assertEquals(ProbeMode.METEOR, config.probes().get(0).mode());
            assertEquals(ProbeVerificationStatus.UNVERIFIED, config.probes().get(0).verificationStatus());
        }
    }
}
