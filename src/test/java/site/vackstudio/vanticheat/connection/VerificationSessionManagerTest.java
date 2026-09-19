package site.vackstudio.vanticheat.connection;

import java.util.UUID;

public class VerificationSessionManagerTest {

    public static void main(String[] args) {
        testCreateSession();
        testGetSession();
        testMarkVerified();
        testMarkRejected();
        testInvalidateSession();
        testCleanupExpired();
        testActiveSessionCount();
        testMaxSessionLimit();
        testHandleVerificationResult();
        System.out.println("All VerificationSessionManager tests passed.");
    }

    static void testCreateSession() {
        VerificationSessionManager mgr = new VerificationSessionManager(100, 60000);
        VerificationSession session = mgr.createSession("player-1");
        assert session != null : "Session should not be null";
        assert session.getState() == VerificationSession.State.CONNECTED : "Initial state should be CONNECTED";
        assert mgr.getSession(session.getSessionId()) != null : "Session should be retrievable";
    }

    static void testGetSession() {
        VerificationSessionManager mgr = new VerificationSessionManager(100, 60000);
        VerificationSession session = mgr.createSession("player-2");
        assert mgr.getSession(session.getSessionId()) == session : "Should retrieve correct session";
        assert mgr.getSession("nonexistent") == null : "Nonexistent session should return null";
    }

    static void testMarkVerified() {
        VerificationSessionManager mgr = new VerificationSessionManager(100, 60000);
        VerificationSession session = mgr.createSession("player-3");
        mgr.markVerified(session.getSessionId());
        assert session.getState() == VerificationSession.State.VERIFIED : "State should be VERIFIED";
        assert mgr.getSession(session.getSessionId()) == null : "Session should be removed after verification";
    }

    static void testMarkRejected() {
        VerificationSessionManager mgr = new VerificationSessionManager(100, 60000);
        VerificationSession session = mgr.createSession("player-4");
        mgr.markRejected(session.getSessionId());
        assert session.getState() == VerificationSession.State.REJECTED : "State should be REJECTED";
        assert mgr.getSession(session.getSessionId()) == null : "Session should be removed after rejection";
    }

    static void testInvalidateSession() {
        VerificationSessionManager mgr = new VerificationSessionManager(100, 60000);
        VerificationSession session = mgr.createSession("player-5");
        mgr.invalidateSession(session.getSessionId());
        assert session.getState() == VerificationSession.State.INVALID : "State should be INVALID";
    }

    static void testCleanupExpired() {
        VerificationSessionManager mgr = new VerificationSessionManager(100, 100);
        VerificationSession session = mgr.createSession("player-6");
        try { Thread.sleep(200); } catch (InterruptedException e) {}
        mgr.cleanupExpired();
        assert mgr.totalSessionCount() == 0 : "Expired sessions should be cleaned up";
    }

    static void testActiveSessionCount() {
        VerificationSessionManager mgr = new VerificationSessionManager(100, 60000);
        mgr.createSession("player-7");
        mgr.createSession("player-8");
        assert mgr.activeSessionCount() == 2 : "Should have 2 active sessions";
    }

    static void testMaxSessionLimit() {
        VerificationSessionManager mgr = new VerificationSessionManager(2, 60000);
        mgr.createSession("p1");
        mgr.createSession("p2");
        try {
            mgr.createSession("p3");
            assert false : "Should throw IllegalStateException for max sessions";
        } catch (IllegalStateException e) {
            // expected
        }
    }

    static void testHandleVerificationResult() {
        VerificationSessionManager mgr = new VerificationSessionManager(100, 60000);
        VerificationSession session = mgr.createSession("player-9");

        var allowResult = mgr.handleVerificationResult(session.getSessionId(), true, "ok");
        assert allowResult.isAllowed() : "Should allow";
        assert session.getState() == VerificationSession.State.VERIFIED : "Should be VERIFIED";

        VerificationSession session2 = mgr.createSession("player-10");
        var rejectResult = mgr.handleVerificationResult(session2.getSessionId(), false, "forbidden");
        assert rejectResult.isRejected() : "Should reject";
    }
}