package site.vackstudio.vanticheat.detection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionSessionTest {
    @Test
    void completesWithImmutableEvidence() {
        DetectionSession session = session();
        Evidence evidence = new Evidence(EvidenceType.OBSERVATION, "test", Instant.now(), Map.of("key", "value"));

        session.start();
        session.addEvidence(evidence);
        session.beginCompletion();
        session.complete(DetectionResult.of(DetectionStatus.CLEAN, "no finding"));

        assertEquals(DetectionSessionState.COMPLETED, session.state());
        assertEquals(DetectionStatus.CLEAN, session.result().status());
        assertEquals(List.of(evidence), session.evidence());
        assertThrows(IllegalStateException.class, () -> session.start());
    }

    @Test
    void cancellationTimeoutAndFailureAreNotClean() {
        DetectionSession cancelled = session();
        cancelled.start();
        cancelled.cancel("cancelled by owner");
        assertEquals(DetectionStatus.SKIPPED, cancelled.result().status());

        DetectionSession timedOut = session();
        timedOut.start();
        timedOut.timeout("deadline exceeded");
        assertEquals(DetectionStatus.ERROR, timedOut.result().status());

        DetectionSession failed = session();
        failed.start();
        failed.fail("execution failed");
        assertEquals(DetectionStatus.ERROR, failed.result().status());
    }

    @Test
    void invalidTransitionsAreRejected() {
        DetectionSession session = session();
        assertThrows(IllegalStateException.class, () -> session.beginCompletion());
        session.start();
        assertThrows(IllegalStateException.class, () -> session.complete(DetectionResult.of(DetectionStatus.CLEAN, "no finding")));
        session.cancel("done");
        assertThrows(IllegalStateException.class, () -> session.addEvidence(
                new Evidence(EvidenceType.SYSTEM, "test", Instant.now(), Map.of())));
    }

    private static DetectionSession session() {
        return new DetectionSession(UUID.randomUUID(), UUID.randomUUID(), "sample", Instant.now());
    }
}
