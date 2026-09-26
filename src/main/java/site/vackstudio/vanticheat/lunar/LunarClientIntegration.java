package site.vackstudio.vanticheat.lunar;

import java.util.Optional;
import java.util.UUID;

/**
 * Apollo boundary. Implementations may talk to the official Apollo API, but
 * this interface exposes no Apollo types so the rest of VAntiCheat never
 * links against Apollo classes.
 */
public interface LunarClientIntegration {
    boolean isAvailable();

    boolean hasSupport(UUID playerId);

    /**
     * Sends the server override disabling the Lunar Minimap.
     *
     * @return true when the override was accepted for delivery
     */
    boolean disableMinimap(UUID playerId);

    /** Current client-known minimap state, when Apollo can report it. */
    Optional<Boolean> minimapStatus(UUID playerId);

    void start();

    void stop();
}
