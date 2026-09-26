package site.vackstudio.vanticheat.enforcement;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.Evidence;
import site.vackstudio.vanticheat.detection.EvidenceType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnforcementServiceTest {
    private static final UUID ID = UUID.randomUUID();
    private final AtomicInteger kicks = new AtomicInteger();
    private final EnforcementExecutor executor = (target, message) -> {
        kicks.incrementAndGet();
        return true;
    };
    private final EnforcementTarget target = new EnforcementTarget(ID, "tester", true, new Object());

    @Test
    void confirmedDetectionKicksOnceAndRecordsAction() {
        EnforcementService service = service(true);

        EnforcementOutcome first = service.enforce(target, confirmed(DetectionStatus.DETECTED));
        EnforcementOutcome duplicate = service.enforce(target, confirmed(DetectionStatus.DETECTED));

        assertEquals(EnforcementAction.KICK, first.decision().action());
        assertEquals(EnforcementAction.NONE, duplicate.decision().action());
        assertEquals(1, kicks.get());
        assertEquals("KICK", first.result().evidence().getLast().metadata().get("action"));
    }

    @Test
    void cleanErrorProtectedAndTimeoutResultsDoNotKick() {
        EnforcementService service = service(true);
        for (DetectionStatus status : new DetectionStatus[]{DetectionStatus.CLEAN, DetectionStatus.ERROR,
                DetectionStatus.PROTECTED, DetectionStatus.UNCERTAIN}) {
            assertEquals(EnforcementAction.NONE, service.enforce(target, confirmed(status)).decision().action());
        }
        assertEquals(0, kicks.get());
    }

    @Test
    void initialDetectionWithoutConfirmationDoesNotKick() {
        EnforcementService service = service(true);
        DetectionResult initial = DetectionResult.of(DetectionStatus.DETECTED, "initial")
                .withEvidence(evidence("INITIAL", "DETECTED"));

        assertEquals(EnforcementAction.NONE, service.enforce(target, initial).decision().action());
        assertEquals(0, kicks.get());
    }

    @Test
    void disabledEnforcementDoesNotKickConfirmedDetection() {
        EnforcementService service = service(false);

        assertEquals(EnforcementAction.NONE,
                service.enforce(target, confirmed(DetectionStatus.DETECTED)).decision().action());
        assertEquals(0, kicks.get());
    }

    @Test
    void offlineTargetRetainsConfirmedResultWithoutKicking() {
        EnforcementService service = service(true);
        EnforcementTarget offline = new EnforcementTarget(ID, "tester", false, new Object());

        EnforcementOutcome outcome = service.enforce(offline, confirmed(DetectionStatus.DETECTED));

        assertEquals(EnforcementAction.NONE, outcome.decision().action());
        assertEquals(DetectionStatus.DETECTED, outcome.result().status());
        assertEquals(0, kicks.get());
    }

    @Test
    void aReconnectWithANewSessionCanBeEnforcedAgain() {
        EnforcementService service = service(true);

        service.enforce(target, confirmedSession("first"));
        EnforcementOutcome reconnect = service.enforce(target, confirmedSession("second"));

        assertEquals(EnforcementAction.KICK, reconnect.decision().action());
        assertEquals(2, kicks.get());
    }

    private EnforcementService service(boolean enabled) {
        return new EnforcementService(new DefaultEnforcementPolicy(enabled), executor,
                "Cheating detected.", Logger.getLogger("test-enforcement"));
    }

    private static DetectionResult confirmed(DetectionStatus status) {
        return DetectionResult.of(status, "result")
                .withEvidence(evidence("CONFIRMATION", status.name()));
    }

    private static Evidence evidence(String pass, String classification) {
        return new Evidence(EvidenceType.OBSERVATION, "test", Instant.EPOCH,
                Map.of("pass", pass, "classification", classification));
    }

    private static DetectionResult confirmedSession(String session) {
        return DetectionResult.of(DetectionStatus.DETECTED, "result")
                .withEvidence(new Evidence(EvidenceType.OBSERVATION, "test", Instant.EPOCH,
                        Map.of("pass", "CONFIRMATION", "classification", "DETECTED", "session", session,
                                "probe", "test-probe")));
    }
}
