package site.vackstudio.vanticheat.lunar;

import java.util.Optional;
import java.util.UUID;
import java.util.Objects;

/**
 * Apollo boundary. Implementations may talk to the official Apollo API, but
 * this interface exposes no Apollo types so the rest of VAntiCheat never
 * links against Apollo classes.
 */
public interface LunarClientIntegration {
    enum Availability { AVAILABLE, NOT_PRESENT, NOT_READY, FAILED, DISABLED }
    enum MinimapAction { APPLIED, ALREADY_APPLIED, FAILED }

    /** Opaque platform player reference captured from the Apollo lifecycle event. */
    record Registration(UUID playerId, String playerName, Object playerHandle) {
        public Registration {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerName, "playerName");
            Objects.requireNonNull(playerHandle, "playerHandle");
        }
    }

    Availability availability();

    boolean hasSupport(UUID playerId);

    /**
     * Sends the server override disabling the Lunar Minimap.
     *
     * @return true when the override was accepted for delivery
     */
    MinimapAction disableMinimap(UUID playerId, Object playerHandle);

    /** Current client-known minimap state, when Apollo can report it. */
    Optional<Boolean> minimapStatus(UUID playerId);

    void start();

    void stop();
}
