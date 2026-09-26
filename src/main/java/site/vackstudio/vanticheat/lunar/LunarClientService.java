package site.vackstudio.vanticheat.lunar;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * Applies the Lunar Minimap server policy for Apollo-recognized players.
 *
 * <p>Lunar Client is allowed. The Lunar Minimap is prohibited by server
 * policy and disabled via Apollo override. Players are never kicked and
 * never classified as cheats by this service.
 */
public final class LunarClientService {
    public enum RegistrationOutcome {
        IGNORED_DISABLED,
        IGNORED_UNAVAILABLE,
        IGNORED_UNSUPPORTED,
        REGISTERED,
        POLICY_APPLIED
    }

    public record LunarSnapshot(boolean available, boolean lunar, boolean minimapPolicyEnabled,
                                LunarPlayerState state) { }

    private final Logger logger;
    private final ConcurrentMap<UUID, LunarPlayerState> states = new ConcurrentHashMap<>();
    private volatile LunarPolicyConfig config = LunarPolicyConfig.defaults();
    private volatile LunarClientIntegration integration;

    public LunarClientService(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized void start(LunarPolicyConfig config, LunarClientIntegration integration) {
        this.config = Objects.requireNonNull(config, "config");
        this.integration = integration;
        if (integration != null) integration.start();
        if (!config.enabled()) {
            logger.info("[VAntiCheat] Lunar integration: DISABLED");
        } else if (integration == null || !integration.isAvailable()) {
            logger.info("[VAntiCheat] Lunar integration: UNAVAILABLE reason=APOLLO_NOT_PRESENT");
        } else {
            logger.info("[VAntiCheat] Lunar integration: READY");
        }
    }

    public synchronized void stop() {
        LunarClientIntegration current = integration;
        integration = null;
        states.clear();
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException exception) {
                logger.warning("[VAntiCheat] Lunar integration stop failed: " + exception.getMessage());
            }
        }
    }

    public RegistrationOutcome handleRegistration(UUID playerId, String playerName) {
        Objects.requireNonNull(playerId, "playerId");
        LunarClientIntegration current = integration;
        if (!config.enabled()) return RegistrationOutcome.IGNORED_DISABLED;
        if (current == null || !current.isAvailable()) return RegistrationOutcome.IGNORED_UNAVAILABLE;
        if (!current.hasSupport(playerId)) return RegistrationOutcome.IGNORED_UNSUPPORTED;
        states.put(playerId, LunarPlayerState.REGISTERED);
        logger.info("[VAntiCheat] Lunar player registered player=" + playerName);
        if (!config.minimapEnabled()) return RegistrationOutcome.REGISTERED;
        boolean applied;
        try {
            applied = current.disableMinimap(playerId);
        } catch (RuntimeException exception) {
            logger.warning("[VAntiCheat] Lunar minimap override failed player=" + playerName
                    + ": " + exception.getMessage());
            return RegistrationOutcome.REGISTERED;
        }
        if (applied) {
            states.put(playerId, LunarPlayerState.MINIMAP_DISABLED);
            logger.info("[VAntiCheat] Lunar policy applied player=" + playerName
                    + " action=DISABLE_MINIMAP success=true");
            return RegistrationOutcome.POLICY_APPLIED;
        }
        logger.info("[VAntiCheat] Lunar policy applied player=" + playerName
                + " action=DISABLE_MINIMAP success=false");
        return RegistrationOutcome.REGISTERED;
    }

    public void handleUnregister(UUID playerId) {
        if (playerId != null) states.remove(playerId);
    }

    public void handleQuit(UUID playerId) {
        handleUnregister(playerId);
    }

    public boolean isLunar(UUID playerId) {
        LunarClientIntegration current = integration;
        if (playerId == null || current == null || !current.isAvailable()) return false;
        try {
            return current.hasSupport(playerId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public LunarSnapshot snapshot(UUID playerId) {
        LunarClientIntegration current = integration;
        boolean available = current != null && current.isAvailable();
        boolean lunar = available && isLunar(playerId);
        LunarPlayerState state = playerId == null
                ? LunarPlayerState.UNKNOWN
                : states.getOrDefault(playerId, LunarPlayerState.UNKNOWN);
        return new LunarSnapshot(available, lunar, config.minimapEnabled(), state);
    }

    public int trackedPlayers() {
        return states.size();
    }
}
