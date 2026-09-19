package site.vackstudio.vanticheat.connection;

import java.util.concurrent.atomic.AtomicReference;
import site.vackstudio.vanticheat.protocol.VerificationProtocol;

public class VerificationSession {

    public enum State {
        CONNECTED,
        VERIFYING,
        VERIFIED,
        REJECTED,
        INVALID,
        BLOCKED,
        TIMEOUT
    }

    private final String sessionId;
    private final String playerId;
    private final long createdAt;
    private final long timeoutAt;
    private final String challenge;
    private final AtomicReference<State> stateRef;
    private volatile String rejectedReason;

    /**
     * Holds the ONE challenge that was sent to the client during login.
     * The client's RESPONSE must contain the exact same challenge value.
     * There must be no second challenge generated later.
     */
    public String getChallenge() { return challenge; }

    public VerificationSession(String sessionId, String playerId, long createdAt, String challenge) {
        this.sessionId = sessionId;
        this.playerId = playerId;
        this.createdAt = createdAt;
        this.timeoutAt = createdAt + 30000;
        this.challenge = challenge;
        this.stateRef = new AtomicReference<>(State.CONNECTED);
    }

    /**
     * Factory method: creates a session with a cryptographically random challenge.
     * This is the SINGLE point of challenge generation.
     * Only one challenge is generated per session.
     */
    public static VerificationSession create(String sessionId, String playerId, long createdAt) {
        VerificationProtocol proto = new VerificationProtocol(1, 3000);
        byte[] challengeBytes = proto.generateChallenge();
        String challenge = proto.challengeToString(challengeBytes);
        VerificationSession session = new VerificationSession(sessionId, playerId, createdAt, challenge);
        session.setState(VerificationSession.State.VERIFYING);
        return session;
    }

    /**
     * Factory method: creates a session with a pre-generated challenge.
     */
    public static VerificationSession create(String sessionId, String playerId, long createdAt, String challenge) {
        VerificationSession session = new VerificationSession(sessionId, playerId, createdAt, challenge);
        session.setState(VerificationSession.State.VERIFYING);
        return session;
    }

    public String getSessionId() { return sessionId; }
    public String getPlayerId() { return playerId; }
    public long getCreatedAt() { return createdAt; }
    public long getTimeoutAt() { return timeoutAt; }
    public State getState() { return stateRef.get(); }
    public void setState(State state) {
        // Direct set is retained for test setup only; production paths must use
        // the atomic try* methods below so exactly one terminal transition wins.
        this.stateRef.set(state);
    }

    /**
     * Atomically transitions CONNECTED/VERIFYING to the given terminal state.
     * Returns true only for the single winning transition.
     */
    public boolean tryTransitionTo(State terminal) {
        if (terminal != State.VERIFIED && terminal != State.REJECTED && terminal != State.INVALID
                && terminal != State.BLOCKED && terminal != State.TIMEOUT) {
            return false;
        }
        State current;
        do {
            current = stateRef.get();
            if (current != State.CONNECTED && current != State.VERIFYING) {
                return false;
            }
        } while (!stateRef.compareAndSet(current, terminal));
        return true;
    }
    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String rejectedReason) { this.rejectedReason = rejectedReason; }

    public boolean isActive() {
        State current = stateRef.get();
        return current == State.CONNECTED || current == State.VERIFYING;
    }

    public boolean isTerminal() {
        State current = stateRef.get();
        return current == State.VERIFIED || current == State.REJECTED || current == State.INVALID
                || current == State.BLOCKED || current == State.TIMEOUT;
    }

    public boolean isExpired(long now) {
        return now > timeoutAt && !isTerminal();
    }

    public void markVerified() { tryTransitionTo(State.VERIFIED); }
    public void markRejected() {
        tryTransitionTo(State.REJECTED);
    }
    public void markBlocked() {
        tryTransitionTo(State.BLOCKED);
    }
    public void markTimeout() {
        tryTransitionTo(State.TIMEOUT);
    }
    public void markInvalid() {
        tryTransitionTo(State.INVALID);
    }
    public void invalidate() {
        tryTransitionTo(State.INVALID);
    }
    public boolean isExpired() {
        return System.currentTimeMillis() > timeoutAt && !isTerminal();
    }
}