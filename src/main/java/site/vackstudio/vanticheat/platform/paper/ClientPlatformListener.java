package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import site.vackstudio.vanticheat.platform.ClientPlatformService;

import java.util.UUID;
import java.util.logging.Logger;

/** Performs UUID-only classification at pre-login and refreshes at join. */
public final class ClientPlatformListener implements Listener {
    private final ClientPlatformService platforms;
    private final Logger logger;

    public ClientPlatformListener(Plugin plugin, ClientPlatformService platforms, Logger logger) {
        this.platforms = platforms;
        this.logger = logger;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // A later login plugin may reject this attempt, in which case no quit event
        // exists to release a UUID cache entry. Join refreshes the live cache.
        log(event.getUniqueId(), event.getName(), platforms.inspect(event.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        log(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                platforms.refresh(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        platforms.remove(event.getPlayer().getUniqueId());
    }

    private void log(UUID id, String name, ClientPlatformService.Classification classification) {
        if (classification.state() == ClientPlatformService.State.UNKNOWN) {
            logger.info("Platform PENDING player=" + name + " uuid=" + id + " reason=" + classification.reason());
        } else {
            logger.info("Platform CLASSIFIED player=" + name + " platform="
                    + (classification.state() == ClientPlatformService.State.NO_PROVIDER ? "JAVA" : classification.state())
                    + " source=" + classification.source());
        }
    }
}
