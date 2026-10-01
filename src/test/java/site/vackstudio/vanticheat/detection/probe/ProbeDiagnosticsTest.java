package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProbeDiagnosticsTest {
    @Test
    void activeScanAndResultMetricsAreImmutableAndUseMeasuredElapsedTime() {
        ProbeDiagnostics diagnostics = new ProbeDiagnostics();
        UUID session = UUID.randomUUID();
        long started = System.nanoTime();
        diagnostics.scanStarted(session, UUID.randomUUID(), "test-player", "MANUAL", 4, started);
        diagnostics.batchStarted(session, 1, "INITIAL", List.of("one", "two"));

        ProbeDiagnostics.Snapshot active = diagnostics.snapshot();
        assertEquals(1, active.activeScans().size());
        assertEquals(1, active.activeScans().get(0).batchIndex());
        assertEquals(List.of("one", "two"), active.activeScans().get(0).currentProbes());
        assertThrows(UnsupportedOperationException.class,
                () -> active.results().put(DetectionStatus.ERROR, 10L));

        diagnostics.scanCompleted(session, DetectionResult.of(DetectionStatus.TIMEOUT, "timeout"),
                started + TimeUnit.MILLISECONDS.toNanos(1_250));
        ProbeDiagnostics.Snapshot completed = diagnostics.snapshot();
        assertEquals(0, completed.activeScans().size());
        assertEquals(1, completed.timeouts());
        assertEquals(1, completed.completedScans());
        assertEquals(1_250, completed.lastDurationMillis());
        assertEquals(1_250, completed.averageDurationMillis());
        assertEquals(DetectionStatus.TIMEOUT, completed.recentScans().get(0).result());
    }

    @Test
    void recentHistoryIsBoundedAndStandaloneSkipsAreCounted() {
        ProbeDiagnostics diagnostics = new ProbeDiagnostics();
        for (int i = 0; i < ProbeDiagnostics.RECENT_SCAN_LIMIT + 5; i++) {
            UUID session = UUID.randomUUID();
            long started = System.nanoTime();
            diagnostics.scanStarted(session, UUID.randomUUID(), "p" + i, "AUTOMATIC", 3, started);
            diagnostics.scanCompleted(session, DetectionResult.of(DetectionStatus.CLEAN, "complete"), started);
        }
        diagnostics.standaloneScan(UUID.randomUUID(), "skipped-player", "AUTOMATIC", 0,
                DetectionStatus.SKIPPED);

        ProbeDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(ProbeDiagnostics.RECENT_SCAN_LIMIT, snapshot.recentScans().size());
        assertEquals(DetectionStatus.SKIPPED, snapshot.recentScans().get(0).result());
        assertEquals("p" + (ProbeDiagnostics.RECENT_SCAN_LIMIT + 4), snapshot.recentScans().get(1).playerName());
        assertEquals(ProbeDiagnostics.RECENT_SCAN_LIMIT + 5, snapshot.cleans());
        assertEquals(1, snapshot.count(DetectionStatus.SKIPPED));
    }

    @Test
    void concurrentCompletionsKeepHistoryBoundedAndCountersAccurate() throws Exception {
        ProbeDiagnostics diagnostics = new ProbeDiagnostics();
        var executor = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 200; i++) {
                executor.submit(() -> {
                    UUID session = UUID.randomUUID();
                    long started = System.nanoTime();
                    diagnostics.scanStarted(session, UUID.randomUUID(), "parallel", "MANUAL", 1, started);
                    diagnostics.scanCompleted(session, DetectionResult.of(DetectionStatus.ERROR, "error"), started);
                });
            }
        } finally {
            executor.shutdown();
            org.junit.jupiter.api.Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        ProbeDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(200, snapshot.errors());
        assertEquals(200, snapshot.completedScans());
        assertEquals(ProbeDiagnostics.RECENT_SCAN_LIMIT, snapshot.recentScans().size());
    }

    @Test
    void distinctTerminalCategoriesRemainDistinct() {
        ProbeDiagnostics diagnostics = new ProbeDiagnostics();
        for (DetectionStatus status : List.of(DetectionStatus.DETECTED, DetectionStatus.PROTECTED,
                DetectionStatus.UNSUPPORTED, DetectionStatus.ERROR, DetectionStatus.TIMEOUT,
                DetectionStatus.CLEAN, DetectionStatus.SKIPPED)) {
            diagnostics.standaloneScan(UUID.randomUUID(), "p", "MANUAL", 1, status);
        }
        var snapshot = diagnostics.snapshot();
        assertEquals(1, snapshot.detections());
        assertEquals(1, snapshot.protectedResults());
        assertEquals(1, snapshot.errors());
        assertEquals(1, snapshot.timeouts());
        assertEquals(1, snapshot.cleans());
        assertEquals(1, snapshot.count(DetectionStatus.UNSUPPORTED));
        assertEquals(1, snapshot.count(DetectionStatus.SKIPPED));
    }

    @Test
    void duplicateTerminalNotificationDoesNotDuplicateCountersOrHistory() {
        ProbeDiagnostics diagnostics = new ProbeDiagnostics();
        UUID session = UUID.randomUUID();
        long started = System.nanoTime();
        diagnostics.scanStarted(session, UUID.randomUUID(), "p", "MANUAL", 1, started);
        DetectionResult result = DetectionResult.of(DetectionStatus.ERROR, "failure");

        diagnostics.scanCompleted(session, result, started);
        diagnostics.scanCompleted(session, result, started);

        assertEquals(1, diagnostics.snapshot().errors());
        assertEquals(1, diagnostics.snapshot().completedScans());
        assertEquals(1, diagnostics.snapshot().recentScans().size());
    }

    @Test
    void cancelledAndShutdownScansDoNotRemainInActiveSnapshots() {
        ProbeDiagnostics diagnostics = new ProbeDiagnostics();
        UUID cancelled = UUID.randomUUID();
        diagnostics.scanStarted(cancelled, UUID.randomUUID(), "leaver", "MANUAL", 2, System.nanoTime());
        diagnostics.scanCancelled(cancelled);
        UUID shutdown = UUID.randomUUID();
        diagnostics.scanStarted(shutdown, UUID.randomUUID(), "shutdown", "AUTOMATIC", 1, System.nanoTime());
        diagnostics.clearActive();

        assertEquals(0, diagnostics.snapshot().activeScans().size());
        assertEquals(0, diagnostics.snapshot().completedScans());
    }
}
