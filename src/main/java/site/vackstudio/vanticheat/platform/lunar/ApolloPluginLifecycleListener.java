package site.vackstudio.vanticheat.platform.lunar;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import site.vackstudio.vanticheat.lunar.LunarClientService;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Tracks only Apollo plugin enable/disable transitions; no polling is used. */
public final class ApolloPluginLifecycleListener implements Listener {
    private static final Set<String> APOLLO_PLUGIN_NAMES = Set.of("apollo", "apollo-bukkit", "apollo-folia");
    private final LunarClientService lunar;

    public ApolloPluginLifecycleListener(LunarClientService lunar) {
        this.lunar = Objects.requireNonNull(lunar, "lunar");
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (isApollo(event.getPlugin().getName())) lunar.apolloDisabled();
    }

    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        if (isApollo(event.getPlugin().getName())) lunar.apolloEnabled();
    }

    static boolean isApollo(String pluginName) {
        return pluginName != null && APOLLO_PLUGIN_NAMES.contains(pluginName.toLowerCase(Locale.ROOT));
    }
}
