package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeResultAggregatorTest {
    @Test
    void detectionIsNotErasedByCleanTimeoutErrorUnsupportedOrSkip() {
        assertEquals(DetectionStatus.DETECTED, aggregate(DetectionStatus.CLEAN, DetectionStatus.DETECTED));
        assertEquals(DetectionStatus.DETECTED, aggregate(DetectionStatus.DETECTED, DetectionStatus.TIMEOUT));
        assertEquals(DetectionStatus.DETECTED, aggregate(DetectionStatus.DETECTED, DetectionStatus.ERROR));
        assertEquals(DetectionStatus.DETECTED, aggregate(DetectionStatus.DETECTED, DetectionStatus.SKIPPED));
    }

    @Test
    void noDetectionStatesRemainDistinguishable() {
        assertEquals(DetectionStatus.CLEAN, aggregate(DetectionStatus.CLEAN, DetectionStatus.SKIPPED));
        assertEquals(DetectionStatus.TIMEOUT, aggregate(DetectionStatus.CLEAN, DetectionStatus.TIMEOUT));
        assertEquals(DetectionStatus.ERROR, aggregate(DetectionStatus.CLEAN, DetectionStatus.ERROR));
        assertEquals(DetectionStatus.UNSUPPORTED, aggregate(DetectionStatus.CLEAN, DetectionStatus.UNSUPPORTED));
        assertEquals(DetectionStatus.SKIPPED, aggregate(DetectionStatus.SKIPPED, DetectionStatus.SKIPPED));
        assertEquals(DetectionStatus.UNCERTAIN, aggregate(DetectionStatus.CLEAN, DetectionStatus.UNCERTAIN));
    }

    @Test
    void onlyActualDetectionAndProtectedResponsesRequireConfirmation() {
        assertTrue(ProbeResultAggregator.needsConfirmation(DetectionStatus.DETECTED));
        assertTrue(ProbeResultAggregator.needsConfirmation(DetectionStatus.PROTECTED));
        assertFalse(ProbeResultAggregator.needsConfirmation(DetectionStatus.TIMEOUT));
        assertFalse(ProbeResultAggregator.needsConfirmation(DetectionStatus.ERROR));
        assertFalse(ProbeResultAggregator.needsConfirmation(DetectionStatus.UNCERTAIN));
        assertFalse(ProbeResultAggregator.needsConfirmation(DetectionStatus.UNSUPPORTED));
        assertFalse(ProbeResultAggregator.needsConfirmation(DetectionStatus.SKIPPED));
    }

    private static DetectionStatus aggregate(DetectionStatus... statuses) {
        return ProbeResultAggregator.aggregate(List.of(statuses));
    }
}
