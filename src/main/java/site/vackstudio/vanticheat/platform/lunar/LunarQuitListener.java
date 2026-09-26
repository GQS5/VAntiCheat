package site.vackstudio.vanticheat.platform.lunar;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import site.vackstudio.vanticheat.lunar.LunarClientService;

import java.util.Objects;

/**
 * Belt-and-suspenders cleanup for Lunar policy state on quit. Apollo fires
 * its own unregister event, but stale UUID state must never survive a
 * disconnect regardless of event ordering.
 */
public final class LunarQuitListener implements Listener {
    private final LunarClientService lunar;

    public LunarQuitListener(LunarClientService lunar) {
        this.lunar = Objects.requireNonNull(lunar, "lunar");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lunar.handleQuit(event.getPlayer().getUniqueId());
    }
}
