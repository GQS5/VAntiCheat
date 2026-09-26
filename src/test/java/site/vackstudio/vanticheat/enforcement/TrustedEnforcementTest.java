package site.vackstudio.vanticheat.enforcement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.Evidence;
import site.vackstudio.vanticheat.detection.EvidenceType;
import site.vackstudio.vanticheat.trusted.PersistentTrustedPlayerService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedEnforcementTest {
    @TempDir Path temporaryDirectory;

    @Test
    void trustedDetectionStaysDetectedButCannotKickUntilTrustIsRemoved() {
        UUID player = UUID.randomUUID();
        PersistentTrustedPlayerService trusted = new PersistentTrustedPlayerService(
                temporaryDirectory.resolve("trusted.yml"), Logger.getAnonymousLogger());
        trusted.add(player, "trusted");
        AtomicInteger kicks = new AtomicInteger();
        EnforcementService enforcement = new EnforcementService(new DefaultEnforcementPolicy(true),
                (target, message) -> { kicks.incrementAndGet(); return true; }, "kick", Logger.getAnonymousLogger(), trusted);
        EnforcementTarget target = new EnforcementTarget(player, "trusted", true, new Object());

        EnforcementOutcome bypassed = enforcement.enforce(target, confirmed("first"));
        assertEquals(EnforcementAction.NONE, bypassed.decision().action());
        assertEquals(DetectionStatus.DETECTED, bypassed.result().status());
        assertEquals(0, kicks.get());

        trusted.remove(player);
        EnforcementOutcome enforced = enforcement.enforce(target, confirmed("second"));
        assertEquals(EnforcementAction.KICK, enforced.decision().action());
        assertEquals(1, kicks.get());
        assertTrue(enforced.result().evidence().stream().anyMatch(value ->
                "KICK".equals(value.metadata().get("action"))));
    }

    private static DetectionResult confirmed(String session) {
        return DetectionResult.of(DetectionStatus.DETECTED, "confirmed")
                .withEvidence(new Evidence(EvidenceType.OBSERVATION, "test", Instant.EPOCH,
                        Map.of("pass", "CONFIRMATION", "classification", "DETECTED", "session", session)));
    }
}
