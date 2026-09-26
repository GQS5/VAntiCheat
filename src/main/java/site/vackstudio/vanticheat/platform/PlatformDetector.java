package site.vackstudio.vanticheat.platform;

import org.bukkit.Server;

public final class PlatformDetector {
    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    private PlatformDetector() { }

    public static Platform detect(Server server) {
        if (server == null) return Platform.UNKNOWN;
        ClassLoader loader = server.getClass().getClassLoader();
        try {
            Class.forName(FOLIA_MARKER, false, loader);
            return Platform.FOLIA;
        } catch (ClassNotFoundException ignored) {
            return Platform.PAPER;
        }
    }

    static Platform detect(boolean serverPresent, boolean foliaMarkerPresent) {
        if (!serverPresent) return Platform.UNKNOWN;
        return foliaMarkerPresent ? Platform.FOLIA : Platform.PAPER;
    }
}
