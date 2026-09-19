package site.vackstudio.vanticheat.connection;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationSessionTest {
    public static void main(String[] args) {
        testInitialState();
        testStateTransitions();
        testChallengeUniqueness();
        testIsActive();
        testIsTerminal();
        testTimeout();
        System.out.println("All VerificationSession tests passed.");
    }

    static void testInitialState() {
        VerificationSession session = VerificationSession.create("s1", "p1", System.currentTimeMillis());
        assert session.getState() == VerificationSession.State.CONNECTED : "Initial state should be CONNECTED";
        assert session.getSessionId().equals("s1") : "Session ID should match";
        assert session.getPlayerId().equals("p1") : "Player ID should match";
        assert session.getChallenge() != null && !session.getChallenge().isEmpty() : "Challenge should exist";
    }

    static void testStateTransitions() {
        VerificationSession session = VerificationSession.create("s2", "p2", System.currentTimeMillis());
        session.markVerified();
        assert session.getState() == VerificationSession.State.VERIFIED : "Should be VERIFIED";

        VerificationSession session2 = VerificationSession.create("s3", "p3", System.currentTimeMillis());
        session2.markRejected();
        assert session2.getState() == VerificationSession.State.REJECTED : "Should be REJECTED";

        VerificationSession session3 = VerificationSession.create("s4", "p4", System.currentTimeMillis());
        session3.markTimeout();
        assert session3.getState() == VerificationSession.State.TIMEOUT : "Should be TIMEOUT";

        VerificationSession session4 = VerificationSession.create("s5", "p5", System.currentTimeMillis());
        session4.markInvalid();
        assert session4.getState() == VerificationSession.State.INVALID : "Should be INVALID";
    }

    static void testChallengeUniqueness() {
        VerificationSession s1 = VerificationSession.create("s1", "p1", System.currentTimeMillis());
        VerificationSession s2 = VerificationSession.create("s2", "p2", System.currentTimeMillis());
        assert !s1.getChallenge().equals(s2.getChallenge()) : "Challenges should be unique";
    }

    static void testIsActive() {
        VerificationSession session = VerificationSession.create("s6", "p6", System.currentTimeMillis());
        assert session.isActive() : "CONNECTED state should be active";
        session.setState(VerificationSession.State.VERIFYING);
        assert session.isActive() : "VERIFYING state should be active";
        session.setState(VerificationSession.State.VERIFIED);
        assert !session.isActive() : "VERIFIED state should not be active";
    }

    static void testIsTerminal() {
        VerificationSession session = VerificationSession.create("s7", "p7", System.currentTimeMillis());
        assert !session.isTerminal() : "CONNECTED should not be terminal";
        session.setState(VerificationSession.State.VERIFIED);
        assert session.isTerminal() : "VERIFIED should be terminal";
        session.setState(VerificationSession.State.REJECTED);
        assert session.isTerminal() : "REJECTED should be terminal";
    }

    static void testTimeout() {
        long past = System.currentTimeMillis() - 100000;
        VerificationSession session = VerificationSession.create("s8", "p8", past);
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        assert session.isExpired() : "Session should be expired";
    }
}