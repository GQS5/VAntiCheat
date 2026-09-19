package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.connection.VerificationSession;
import site.vackstudio.vanticheat.connection.VerificationSessionManager;
import site.vackstudio.vanticheat.config.PluginConfig;
import site.vackstudio.vanticheat.policy.Blocklist;
import site.vackstudio.vanticheat.policy.MatchResult;
import site.vackstudio.vanticheat.VLogger;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.UUID;

public class DisconnectHandler {

    private final ProxyServer server;
    private final VerificationSessionManager sessionManager;
    private final Blocklist blocklist;
    private final KickMessages kickMessages;

    public DisconnectHandler(ProxyServer server, VerificationSessionManager sessionManager,
                            Blocklist blocklist, KickMessages kickMessages) {
        this.server = server;
        this.sessionManager = sessionManager;
        this.blocklist = blocklist;
        this.kickMessages = kickMessages;
    }

    public void disconnect(Player player, String sessionId, String reason) {
        if (player == null) return;
        try {
            Component disconnectMessage = Component.text(reason);
            player.disconnect(disconnectMessage);
        } catch (Exception e) {
            VLogger.error("Failed to disconnect player", e);
        } finally {
            sessionManager.invalidateSession(sessionId);
        }
    }

    public void disconnectPlayer(Player player, String sessionId, EnforcementDecision decision) {
        String reason = resolveReason(decision);
        disconnect(player, sessionId, reason);
    }

    private String resolveReason(EnforcementDecision decision) {
        if (decision.isRejected()) {
            return decision.getReason();
        }
        return kickMessages.verificationFailed();
    }

    public void handleBlockedMod(Player player, String sessionId, MatchResult match) {
        String reason = kickMessages.forbiddenMod() + " Rule: " + match.getBlockedRuleId();
        disconnect(player, sessionId, reason);
    }

    public void handleTimeout(Player player, String sessionId) {
        String reason = kickMessages.verificationTimeout();
        disconnect(player, sessionId, reason);
    }
}