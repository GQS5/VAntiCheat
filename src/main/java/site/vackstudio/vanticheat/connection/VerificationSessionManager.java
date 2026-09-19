package site.vackstudio.vanticheat.connection;

import site.vackstudio.vanticheat.enforcement.EnforcementDecision;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class VerificationSessionManager {

    private final Map<String, VerificationSession> sessions = new ConcurrentHashMap<>();
    private final int maxSessionCount;
    private final long cleanupIntervalMs;
    private final AtomicLong sessionCounter = new AtomicLong(0);

    public VerificationSessionManager(com.velocitypowered.api.plugin.PluginContainer plugin) {
        this(1000, 60000);
    }

    public VerificationSessionManager(int maxSessionCount, long cleanupIntervalMs) {
        this.maxSessionCount = maxSessionCount;
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public VerificationSession createSession(String playerId) {
        if (sessions.size() >= maxSessionCount) {
            cleanupExpired();
        }
        if (sessions.size() >= maxSessionCount) {
            throw new IllegalStateException("Maximum session count reached");
        }
        String sessionId = java.util.UUID.randomUUID().toString();
        long created = System.currentTimeMillis();
        // VerificationProtocol generates the ONE challenge for this session
        site.vackstudio.vanticheat.protocol.VerificationProtocol proto =
                new site.vackstudio.vanticheat.protocol.VerificationProtocol(1, 3000);
        byte[] challengeBytes = proto.generateChallenge();
        String challenge = proto.challengeToString(challengeBytes);
        VerificationSession session = new VerificationSession(sessionId, playerId, created, challenge);
        sessions.put(sessionId, session);
        return session;
    }

    public int getMaxSessionCount() {
        return maxSessionCount;
    }

    public void putSession(VerificationSession session) {
        if (session == null || session.getSessionId() == null) {
            throw new IllegalArgumentException("Session is null");
        }
        if (sessions.size() >= maxSessionCount) {
            cleanupExpired();
        }
        if (sessions.size() >= maxSessionCount) {
            throw new IllegalStateException("Maximum session count reached");
        }
        sessions.put(session.getSessionId(), session);
    }

    public VerificationSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public boolean removeSession(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    public void invalidateSession(String sessionId) {
        VerificationSession s = sessions.get(sessionId);
        if (s != null) {
            s.invalidate();
        }
        sessions.remove(sessionId);
    }

    public void markVerified(String sessionId) {
        VerificationSession s = sessions.get(sessionId);
        if (s != null) {
            s.markVerified();
        }
        sessions.remove(sessionId);
    }

    public void markRejected(String sessionId) {
        VerificationSession s = sessions.get(sessionId);
        if (s != null) {
            s.markRejected();
        }
        sessions.remove(sessionId);
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            VerificationSession s = entry.getValue();
            return s.isExpired(now) || s.isTerminal();
        });
    }

    public int activeSessionCount() {
        return (int) sessions.values().stream().filter(s -> !s.isTerminal()).count();
    }

    public int totalSessionCount() {
        return sessions.size();
    }

    public void shutdown() {
        sessions.clear();
    }

    public boolean isSessionActive(String sessionId) {
        VerificationSession s = sessions.get(sessionId);
        return s != null && s.isActive();
    }

    public void markInvalid(String sessionId) {
        VerificationSession s = sessions.get(sessionId);
        if (s != null) {
            s.markInvalid();
        }
    }

    public void handleDisconnect(String sessionId) {
        invalidateSession(sessionId);
    }

    public EnforcementDecision handleVerificationResult(String sessionId, boolean success, String reason) {
        VerificationSession session = sessions.get(sessionId);
        if (session == null) {
            return EnforcementDecision.REJECT_UNKNOWN;
        }
        if (success) {
            session.markVerified();
            sessions.remove(sessionId);
            return EnforcementDecision.ALLOW;
        }
        session.markRejected();
        sessions.remove(sessionId);
        return EnforcementDecision.REJECT;
    }
}