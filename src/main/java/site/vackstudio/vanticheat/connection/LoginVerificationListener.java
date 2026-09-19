package site.vackstudio.vanticheat.connection;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.event.ResultedEvent;
import site.vackstudio.vanticheat.VAntiCheatPlugin;
import site.vackstudio.vanticheat.VLogger;
import site.vackstudio.vanticheat.protocol.ProtocolConstants;
import site.vackstudio.vanticheat.protocol.ProtocolReader;
import site.vackstudio.vanticheat.protocol.ProtocolWriter;
import site.vackstudio.vanticheat.protocol.ProtocolException;
import site.vackstudio.vanticheat.protocol.VerificationProtocol;
import site.vackstudio.vanticheat.connection.VerificationSession;
import site.vackstudio.vanticheat.connection.VerificationSessionManager;
import site.vackstudio.vanticheat.policy.Blocklist;
import site.vackstudio.vanticheat.policy.MatchResult;
import site.vackstudio.vanticheat.client.ClientReport;
import site.vackstudio.vanticheat.client.ModRecord;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/**
 * Pre-backend login verification gate.
 *
 * <p>Lifecycle (Velocity 3.4.x, verified against the proxy implementation):
 * {@code PreLoginEvent} exposes the connecting client's {@code InboundConnection},
 * which Velocity documents may be cast to {@code LoginPhaseConnection} for
 * login plugin messages. Messages queued from the PreLogin handler are flushed
 * once pre-login completes, and Velocity defers the rest of the login
 * (authentication, {@code LoginEvent}, backend connection) until every
 * outstanding login plugin response is handled. A message sent later, from the
 * {@code LoginEvent} handler, is <em>not</em> waited on, so the challenge must
 * be sent here in PreLogin. This listener therefore never casts {@code Player}
 * to {@code LoginPhaseConnection}. Sessions are bound to the TCP connection
 * that carries the login (remote IP + port), the strongest identity Velocity
 * 3.4 exposes that is stable across both events: the PreLogin client-sent
 * UUID is replaced before LoginEvent in offline mode (and is
 * attacker-influenced), and usernames are not unique across concurrent
 * connections, so neither is used as a lookup key. The username is still
 * enforced as an ownership check after lookup (defense in depth).
 * Cross-connection safety comes from the per-connection pending maps: a
 * LoginEvent only ever resolves sessions stored under its own UUID or
 * remote-address keys, entries are consumed on use, and reaps are
 * value-checked so a reconnecting address can never inherit a stale entry.
 *
 * <p>Login-phase messaging semantics (no stronger claims than the API):
 * VAntiCheat sends one login plugin message via
 * {@code LoginPhaseConnection#sendLoginPluginMessage}; Velocity associates the
 * given response consumer with that message; the consumer executes when the
 * client response arrives (a missing/unsuccessful response yields {@code null}
 * bytes, which are denied); login does not continue while required login
 * plugin messages remain unresolved; the {@code LoginEvent} handler then reads
 * the stored terminal session state and sets the single authoritative result.
 * A scheduler timeout marks the session TIMEOUT (the stalled login is then
 * reaped by Velocity read-timeout since the plugin API exposes no pre-login
 * disconnect). All terminal paths remove pending state so stale sessions
 * cannot be reused.
 *
 * <p>Trust limitation: VAntiCheat verifies and enforces information supplied
 * through its verification protocol. It does not independently prove the
 * internal truthfulness of a compromised client.
 */
public class LoginVerificationListener {

    private final VAntiCheatPlugin plugin;
    private final ScheduledExecutorService scheduler;

    private static final ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> pendingTimeouts =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> pendingSessionIds = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> pendingSessionByAddr = new ConcurrentHashMap<>();

    /**
     * Connection key: normalized remote IP + port of the login's TCP
     * connection. Unique per concurrent connection (ephemeral ports differ),
     * identical for one connection across PreLogin and Login events.
     */
    static String addrKey(java.net.InetSocketAddress addr) {
        if (addr == null || addr.getAddress() == null) {
            return null;
        }
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

    public LoginVerificationListener(VAntiCheatPlugin plugin) {
        this.plugin = plugin;
        // Scheduler is created once at startup and never replaced on reload.
        // Session manager, protocol, and blocklist are resolved per event via
        // plugin getters so configuration reloads apply to new logins.
        this.scheduler = plugin.getScheduler();
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!plugin.isEnabled()) return;
        VerificationSessionManager sessionManager = plugin.getSessionManager();
        VerificationProtocol protocol = plugin.getProtocol();
        try {
            // Bound pre-login connection state so half-open floods cannot grow it without limit.
            int cap = sessionManager.getMaxSessionCount();
            if (pendingSessionIds.size() >= cap || pendingSessionByAddr.size() >= cap) {
                VLogger.warn("PreLogin connection flood - denying fail-closed for player: {}",
                        event.getUsername());
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        net.kyori.adventure.text.Component.text("Server busy")));
                return;
            }
            InboundConnection connection = event.getConnection();
            if (!(connection instanceof LoginPhaseConnection)) {
                VLogger.warn("PreLogin connection is not a LoginPhaseConnection for player: {}",
                        event.getUsername());
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        net.kyori.adventure.text.Component.text("Verification unavailable")));
                return;
            }
            LoginPhaseConnection lpc = (LoginPhaseConnection) connection;
            if (lpc.getProtocolState() != com.velocitypowered.api.network.ProtocolState.LOGIN) {
                return;
            }
            if (scheduler == null) {
                VLogger.error("Scheduler unavailable - denying fail-closed for player: {}",
                        event.getUsername());
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        net.kyori.adventure.text.Component.text("Verification unavailable")));
                return;
            }

            String username = event.getUsername();
            UUID uuid = event.getUniqueId();
            // Bind the session to the login's TCP connection (remote IP + port):
            // it is the only identity that is both unique per concurrent
            // connection and stable across PreLogin and Login events. The
            // PreLogin UUID is client-sent and replaced before LoginEvent in
            // offline mode; usernames collide across concurrent logins. The
            // UUID key is kept as a secondary hint (exact in online mode).
            // The username is enforced as an ownership check in onLogin.
            String playerId = username != null ? username : "unknown";

            // The session MUST carry the exact challenge sent to the client:
            // generating them separately would make every response mismatch.
            byte[] challengeBytes = protocol.generateChallenge();
            String challenge = protocol.challengeToString(challengeBytes);
            VerificationSession session = VerificationSession.create(
                    java.util.UUID.randomUUID().toString(), playerId, System.currentTimeMillis(),
                    challenge);
            try {
                sessionManager.putSession(session);
            } catch (IllegalStateException e) {
                VLogger.warn("Session capacity reached - denying fail-closed for player: {}",
                        username);
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        net.kyori.adventure.text.Component.text("Server busy")));
                return;
            }

            VLogger.info("Starting verification for player: {} (session: {})",
                    username, session.getSessionId());

            byte[] challengePacket = ProtocolWriter.buildChallengePacket(
                    session.getSessionId(), challenge);

            if (uuid != null) {
                pendingSessionIds.put(uuid, session.getSessionId());
            }
            String connectionKey = addrKey(lpc.getRemoteAddress());
            if (connectionKey != null) {
                pendingSessionByAddr.put(connectionKey, session.getSessionId());
            } else {
                VLogger.warn("PreLogin connection has no remote address for player: {}",
                        username);
            }

            java.util.concurrent.ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
                if (session.tryTransitionTo(VerificationSession.State.TIMEOUT)) {
                    session.setRejectedReason("Verification timed out");
                    VLogger.warn("Verification timed out for player: {} (session: {})",
                            username, session.getSessionId());
                }
                pendingTimeouts.remove(session.getSessionId());
                // Records are intentionally kept: onLogin still needs the terminal
                // state + reason for its authoritative decision. A follow-up task
                // reaps them if LoginEvent never arrives (stalled login).
            }, protocol.getTimeoutMs(), TimeUnit.MILLISECONDS);
            pendingTimeouts.put(session.getSessionId(), timeoutFuture);
            scheduler.schedule(() -> {
                pendingTimeouts.remove(session.getSessionId());
                if (uuid != null) {
                    pendingSessionIds.remove(uuid, session.getSessionId());
                }
                if (connectionKey != null) {
                    pendingSessionByAddr.remove(connectionKey, session.getSessionId());
                }
                sessionManager.removeSession(session.getSessionId());
            }, protocol.getTimeoutMs() + 120000L, TimeUnit.MILLISECONDS);

            MinecraftChannelIdentifier identifier =
                    MinecraftChannelIdentifier.from(ProtocolConstants.PROTOCOL_CHANNEL);
            lpc.sendLoginPluginMessage(identifier, challengePacket, response -> {
                handleLoginResponse(response, session);
            });
        } catch (Exception e) {
            VLogger.error("Failed to start verification", e);
            try {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        net.kyori.adventure.text.Component.text("Verification failed")));
            } catch (Exception deniedError) {
                VLogger.warn("Could not deny PreLogin: {}", deniedError.getMessage());
            }
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        if (!plugin.isEnabled()) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String connectionKey = addrKey(player.getRemoteAddress());

        // Single authoritative admission decision, read from stored terminal state.
        // Resolution uses only per-connection keys, address first: the remote
        // address is unique per concurrent TCP connection, while the UUID key
        // can coincide for same-user logins (offline-derived or Mojang UUIDs).
        // The username is never a lookup key, so concurrent logins under one
        // username cannot resolve each other's sessions.
        String sessionId = null;
        if (connectionKey != null) {
            sessionId = pendingSessionByAddr.get(connectionKey);
        }
        if (sessionId == null) {
            sessionId = pendingSessionIds.get(playerId);
        }
        // Consume pending markers so they cannot authorize a later login.
        // Removals are value-checked: a reconnect reusing an address must not
        // delete the new connection's entry, and vice versa.
        if (sessionId != null) {
            pendingSessionIds.remove(playerId, sessionId);
            if (connectionKey != null) {
                pendingSessionByAddr.remove(connectionKey, sessionId);
            }
        }
        if (sessionId != null) {
            cancelTimeout(sessionId);
        }
        if (sessionId == null) {
            VLogger.warn("No verification session for player: {} - denying fail-closed",
                    player.getUsername());
            event.setResult(ResultedEvent.ComponentResult.denied(
                    net.kyori.adventure.text.Component.text("Verification unavailable")));
            return;
        }

        VerificationSessionManager sessionManager = plugin.getSessionManager();
        VerificationSession sess = sessionManager.getSession(sessionId);
        if (sess == null) {
            VLogger.warn("Unknown session for player: {} - denying fail-closed",
                    player.getUsername());
            event.setResult(ResultedEvent.ComponentResult.denied(
                    net.kyori.adventure.text.Component.text("Unknown session")));
            return;
        }
        if (sess.getPlayerId() == null || player.getUsername() == null
                || !sess.getPlayerId().equalsIgnoreCase(player.getUsername())) {
            VLogger.warn("Session {} owner {} does not match player {} ({})", sessionId,
                    sess.getPlayerId(), player.getUsername(), playerId);
            event.setResult(ResultedEvent.ComponentResult.denied(
                    net.kyori.adventure.text.Component.text("Session mismatch")));
            return;
        }
        if (sess.getState().equals(VerificationSession.State.VERIFIED)) {
            sessionManager.markVerified(sessionId);
            VLogger.info("Verification ALLOWED for player: {}", player.getUsername());
            event.setResult(ResultedEvent.ComponentResult.allowed());
            return;
        }
        String reason = sess.getRejectedReason();
        if (reason == null || reason.isEmpty()) {
            reason = "Verification failed";
        }
        VLogger.info("Verification REJECTED for player: {} - {}", player.getUsername(),
                sanitizeForLog(reason));
        event.setResult(ResultedEvent.ComponentResult.denied(
                net.kyori.adventure.text.Component.text(sanitizeForLog(reason))));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        try {
            Player player = event.getPlayer();
            if (player == null) return;
            UUID playerId = player.getUniqueId();
            String connectionKey = addrKey(player.getRemoteAddress());
            // Value-checked cleanup: the address key is unique to this TCP
            // connection, so resolving through it identifies this connection's
            // own session; the UUID entry is then removed only if it points at
            // the same session. Unknown entries are left to the timeout reaper
            // rather than risk deleting another connection's mapping.
            String sessionId = null;
            if (connectionKey != null) {
                sessionId = pendingSessionByAddr.remove(connectionKey);
            }
            if (sessionId != null) {
                pendingSessionIds.remove(playerId, sessionId);
            }
            if (sessionId != null) {
                cancelTimeout(sessionId);
                plugin.getSessionManager().handleDisconnect(sessionId);
            }
        } catch (Exception e) {
            VLogger.warn("Disconnect cleanup failed: {}", e.getMessage());
        }
    }

    private void handleLoginResponse(byte[] response, VerificationSession session) {
        if (!plugin.isEnabled()) return;
        VerificationSessionManager sessionManager = plugin.getSessionManager();
        Blocklist blocklist = plugin.getBlocklist();
        String sessionId = session.getSessionId();

        try {
            if (response == null) {
                throw new ProtocolException(ProtocolException.Type.MALFORMED, "Empty response");
            }
            int packetType = ProtocolReader.readPacketType(response);
            String responseSessionId = ProtocolReader.readSessionId(response);

            VerificationSession sess = sessionManager.getSession(sessionId);
            if (sess == null || sess != session) {
                VLogger.warn("Unknown session in response: {}", sanitizeForLog(responseSessionId));
                return;
            }
            if (sess.isTerminal()) {
                VLogger.warn("Session {} already finalized", sessionId);
                return;
            }
            if (!sess.getState().equals(VerificationSession.State.VERIFYING)) {
                VLogger.warn("Session {} not in VERIFYING state", sessionId);
                return;
            }
            if (!responseSessionId.equals(sessionId)) {
                deny(sessionManager, sess, sessionId, "Session mismatch");
                return;
            }

            String challenge = ProtocolReader.readChallenge(response);
            if (!sess.getChallenge().equals(challenge)) {
                deny(sessionManager, sess, sessionId, "Challenge mismatch");
                return;
            }

            if (packetType == ProtocolConstants.PacketType.RESPONSE.ordinal()) {
                byte[] reportData = ProtocolReader.readReport(response);
                VLogger.info("Verification response received for session: {}", sessionId);

                // Binary-only wire: RESPONSE payload -> BinaryReader -> ClientReport.
                ClientReport report = ClientReport.fromBytes(reportData);
                List<ModRecord> mods = report.getMods();
                List<MatchResult> results = blocklist.checkAll(mods);
                boolean blocked = false;
                for (MatchResult result : results) {
                    if (result.isMatch()) {
                        blocked = true;
                        break;
                    }
                }
                if (blocked) {
                    deny(sessionManager, sess, sessionId, "Forbidden mod detected");
                    VLogger.info("Verification REJECTED for session: {} - forbidden mod detected",
                            sessionId);
                } else {
                    if (sess.tryTransitionTo(VerificationSession.State.VERIFIED)) {
                        VLogger.info("Verification recorded ALLOW for session: {}", sessionId);
                    }
                    cancelTimeout(sessionId);
                }
            } else if (packetType == ProtocolConstants.PacketType.TERMINATE.ordinal()) {
                String reason = ProtocolReader.readTerminateReason(response);
                VLogger.info("Verification terminated by client for session: {} - {}", sessionId,
                        sanitizeForLog(reason));
                deny(sessionManager, sess, sessionId, "Verification rejected by client");
            } else {
                VLogger.warn("Unexpected packet type {} in verification message", packetType);
                deny(sessionManager, sess, sessionId, "Invalid protocol");
            }

        } catch (ProtocolException e) {
            // Attacker-triggered malformed input: concise single-line warning, no
            // stack trace (normal rejection traffic must not spam logs).
            VLogger.warn("Protocol error for session {}: {}", sessionId,
                    sanitizeForLog(e.getMessage()));
            deny(sessionManager, session, sessionId, "Protocol error");
        }
    }

    private void deny(VerificationSessionManager sessionManager, VerificationSession sess,
                      String sessionId, String reason) {
        // Record the terminal decision for onLogin; onLogin performs the removal.
        sess.setRejectedReason(reason);
        sess.tryTransitionTo(VerificationSession.State.REJECTED);
        cancelTimeout(sessionId);
    }

    private static void cancelTimeout(String sessionId) {
        java.util.concurrent.ScheduledFuture<?> future = pendingTimeouts.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Bounds and neutralizes client-controlled text before logging or echoing it.
     * Strips control characters/newlines to prevent log injection and truncates
     * to a fixed maximum so one response cannot bloat logs or kick messages.
     */
    static String sanitizeForLog(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "?");
        if (cleaned.length() > 200) {
            return cleaned.substring(0, 200);
        }
        return cleaned;
    }
}
