package site.vackstudio.vanticheat.platform.paper;

import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.detection.probe.ProbeDiagnostics;
import site.vackstudio.vanticheat.enforcement.EnforcementService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-only global invariant checker. No production use. */
final class ReliabilityInvariants {
    private ReliabilityInvariants() { }

    static void assertTerminalState(String context,
                                    CheckHacksClientDetectionModule module,
                                    PaperSignProbeTransport transport,
                                    int coordinatorActive,
                                    int apolloTracked,
                                    int enforcementInFlight) {
        assertTerminalState(context, module, transport.activeOperations(),
                coordinatorActive, apolloTracked, enforcementInFlight);
    }

    static void assertTerminalState(String context,
                                    CheckHacksClientDetectionModule module,
                                    int transportActiveOps,
                                    int coordinatorActive,
                                    int apolloTracked,
                                    int enforcementInFlight) {
        ProbeDiagnostics.Snapshot snapshot = module.diagnostics();
        assertEquals(0, module.activeSessionCount(), context + ": no active session without an operation");
        assertEquals(0, transportActiveOps, context + ": no active transport operation");
        assertEquals(0, coordinatorActive, context + ": no automatic operation without coordinator ownership");
        assertEquals(0, snapshot.activeScans().size(), context + ": no failed operation remains active");
        assertEquals(0, apolloTracked, context + ": no pending Apollo action after shutdown");
        assertEquals(0, enforcementInFlight, context + ": no released operation with future enforcement");
        assertTrue(snapshot.recentScans().size() <= ProbeDiagnostics.RECENT_SCAN_LIMIT,
                context + ": history remains bounded");
    }

    static void assertEnforcementBounded(EnforcementService service) {
        assertTrue(service.retainedEnforcementKeyCount() <= EnforcementService.MAX_RETAINED_ENFORCEMENT_KEYS,
                "enforcement dedupe remains bounded");
        assertEquals(0, service.inFlightEnforcementCount(), "no enforcement reservation leaks");
    }

    /**
     * Cleanup outcome of one temporary sign operation. Restoration that the platform
     * actually performed, restoration that was attempted but refused, and cleanup that
     * could not be scheduled are distinct states and are asserted separately.
     */
    record CleanupOutcome(int restoreAttempts, int restores, int barrierRestores, int cleanupSchedules,
                          int callbackCount) {
        boolean restored() { return restores == 1 && barrierRestores == 1; }
        boolean attemptedButFailed() { return restoreAttempts == 1 && restores == 0; }
        boolean couldNotBeScheduled() { return cleanupSchedules == 0; }
    }

    /** After a terminal sign operation nothing may remain active or half-owned. */
    static void assertCleanupTerminal(String context, PaperSignProbeTransport transport, CleanupOutcome outcome) {
        assertEquals(0, transport.activeOperations(), context + ": terminal operation released");
        assertTrue(outcome.callbackCount() <= 1, context + ": response callback reported at most once");
        assertEquals(1, outcome.cleanupSchedules(), context + ": cleanup scheduled exactly once");
    }

    /** Successful cleanup must leave the world free of temporary state. */
    static void assertCleanupCompleted(String context, CleanupOutcome outcome) {
        assertCleanupTerminal(context, null, outcome);
        assertTrue(outcome.restored(), context + ": temporary sign and barrier restored exactly once");
    }

    /** An externally refused cleanup stays explicit and never reports success. */
    static void assertCleanupFailed(String context, CleanupOutcome outcome) {
        assertTrue(outcome.attemptedButFailed(),
                context + ": refused restoration is recorded as attempted but not completed");
        assertTrue(!outcome.restored(), context + ": a failed cleanup must never report success");
    }
}
