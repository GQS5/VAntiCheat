package com.hexa.vanticheat.core;

/**
 * Small logging facade honoring general.debug.
 */
public final class VLogger {

    private final VAntiCheat plugin;

    public VLogger(VAntiCheat plugin) {
        this.plugin = plugin;
    }

    public void info(String msg) {
        plugin.getLogger().info(msg);
    }

    public void warn(String msg) {
        plugin.getLogger().warning(msg);
    }

    public void severe(String msg, Throwable t) {
        plugin.getLogger().severe(msg + (t == null ? "" : " (" + t + ")"));
    }

    public void debug(String msg) {
        try {
            if (plugin.getConfig().getBoolean("general.debug", false)) {
                plugin.getLogger().info("[DEBUG] " + msg);
            }
        } catch (Exception ignored) {
        }
    }
}
