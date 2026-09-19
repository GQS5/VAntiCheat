package site.vackstudio.vanticheat;

import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.google.inject.Inject;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;
import site.vackstudio.vanticheat.config.PluginConfig;
import site.vackstudio.vanticheat.config.ConfigLoader;
import site.vackstudio.vanticheat.connection.LoginVerificationListener;
import site.vackstudio.vanticheat.enforcement.EnforcementDecision;
import site.vackstudio.vanticheat.command.VacCommand;
import site.vackstudio.vanticheat.policy.Blocklist;
import site.vackstudio.vanticheat.protocol.ProtocolConstants;
import site.vackstudio.vanticheat.protocol.VerificationProtocol;
import site.vackstudio.vanticheat.connection.VerificationSessionManager;
import site.vackstudio.vanticheat.VLogger;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Plugin(id = "vanticheat", name = "VAntiCheat", version = "0.1.0", description = "Pre-backend client verification and forbidden-mod firewall")
public class VAntiCheatPlugin {

    private static VAntiCheatPlugin instance;
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private PluginContainer container;
    private volatile PluginConfig config;
    private volatile Blocklist blocklist;
    private volatile VerificationSessionManager sessionManager;
    private volatile VerificationProtocol protocol;
    private LoginVerificationListener loginListener;
    private ScheduledExecutorService scheduledExecutor;
    private volatile boolean enabled;
    private final Object configLock = new Object();

    @Inject
    public VAntiCheatPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        instance = this;
        VLogger.setLogger(logger);
        VLogger.info("VAntiCheat 0.1.0 initializing...");

        try {
            Path base = dataDirectory != null ? dataDirectory : Path.of("plugins/VAntiCheat");
            ConfigLoader loader = new ConfigLoader(base);
            this.config = loader.load();
        } catch (Exception e) {
            VLogger.error("Failed to load config, using defaults", e);
            this.config = PluginConfig.defaults();
        }

        this.enabled = config.isGeneralEnabled();
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vanticheat-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.blocklist = new Blocklist(config);
        this.protocol = new VerificationProtocol(config.getProtocolVersion(), config.getTimeoutMs());
        this.sessionManager = new VerificationSessionManager(config.getMaxSessionCount(), config.getCleanupIntervalMs());
        this.loginListener = new LoginVerificationListener(this);

        if (this.enabled && this.config.isVerificationEnabled()) {
            server.getEventManager().register(this, loginListener);
            server.getChannelRegistrar().register(
                    MinecraftChannelIdentifier.from(ProtocolConstants.PROTOCOL_CHANNEL));
        }

        this.scheduledExecutor.scheduleAtFixedRate(() -> {
            try { sessionManager.cleanupExpired(); } catch (Exception e) { VLogger.error("Session cleanup error", e); }
        }, 60, 60, TimeUnit.SECONDS);

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("vac").build(), new VacCommand(this));

        VLogger.info("VAntiCheat 0.1.0 initialized. Enabled: {}", this.enabled);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        VLogger.info("VAntiCheat shutting down...");
        if (scheduledExecutor != null) { scheduledExecutor.shutdownNow(); }
        if (sessionManager != null) { sessionManager.shutdown(); }
        VLogger.info("VAntiCheat shut down complete.");
    }

    public ScheduledExecutorService getScheduler() { return scheduledExecutor; }
    public ProxyServer getProxy() { return server; }
    public Logger getLogger() { return logger; }
    public PluginContainer getContainer() { return container; }

    public void setContainer(PluginContainer container) { this.container = container; }

    public PluginConfig getConfig() {
        synchronized (configLock) { return config; }
    }

    public void reloadConfig() {
        synchronized (configLock) {
            try {
                Path base = dataDirectory != null ? dataDirectory : Path.of("plugins/VAntiCheat");
                ConfigLoader loader = new ConfigLoader(base);
                PluginConfig newConfig = loader.load();
                this.config = newConfig;
                this.enabled = newConfig.isGeneralEnabled();
                this.blocklist = new Blocklist(newConfig);
                this.protocol = new VerificationProtocol(newConfig.getProtocolVersion(), newConfig.getTimeoutMs());
                this.sessionManager = new VerificationSessionManager(newConfig.getMaxSessionCount(), newConfig.getCleanupIntervalMs());
                VLogger.info("Configuration reloaded successfully.");
            } catch (Exception e) {
                VLogger.error("Failed to reload configuration", e);
            }
        }
    }

    public boolean isEnabled() {
        return enabled && config != null && config.isGeneralEnabled() && config.isVerificationEnabled();
    }

    public Blocklist getBlocklist() { return blocklist; }
    public VerificationSessionManager getSessionManager() { return sessionManager; }
    public VerificationProtocol getProtocol() { return protocol; }

    public EnforcementDecision handleVerificationResult(String sessionId, boolean success, String reason) {
        return sessionManager.handleVerificationResult(sessionId, success, reason);
    }

    public void onPlayerDisconnect(String playerId, String sessionId) {
        sessionManager.handleDisconnect(sessionId);
    }
}