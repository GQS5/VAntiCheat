package site.vackstudio.vanticheat;

import org.bukkit.plugin.java.JavaPlugin;
import site.vackstudio.vanticheat.config.ConfigurationLoader;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.config.EnforcementConfig;
import site.vackstudio.vanticheat.config.Messages;
import site.vackstudio.vanticheat.core.VAntiCheatCore;
import site.vackstudio.vanticheat.platform.PaperFoliaScheduler;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.PlatformDetector;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.platform.paper.PaperSignProbeTransport;
import site.vackstudio.vanticheat.platform.paper.ClientProbeCommand;
import site.vackstudio.vanticheat.platform.paper.PaperEnforcementExecutor;
import site.vackstudio.vanticheat.platform.paper.AutomaticClientDetectionListener;
import site.vackstudio.vanticheat.enforcement.DefaultEnforcementPolicy;
import site.vackstudio.vanticheat.enforcement.EnforcementService;
import site.vackstudio.vanticheat.platform.paper.VAntiCheatCommand;
import site.vackstudio.vanticheat.trusted.PersistentTrustedPlayerService;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;
import site.vackstudio.vanticheat.lunar.LunarClientService;
import site.vackstudio.vanticheat.lunar.LunarClientIntegration;
import site.vackstudio.vanticheat.lunar.LunarPolicyConfig;
import site.vackstudio.vanticheat.platform.lunar.ApolloBridgeLoader;
import site.vackstudio.vanticheat.platform.lunar.LunarQuitListener;
import site.vackstudio.vanticheat.platform.lunar.ApolloPluginLifecycleListener;
import site.vackstudio.vanticheat.platform.ClientPlatformService;
import site.vackstudio.vanticheat.platform.paper.PaperClientPlatformProviders;
import site.vackstudio.vanticheat.platform.paper.ClientPlatformListener;
import site.vackstudio.vanticheat.diagnostics.VAntiCheatDiagnostics;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class VAntiCheatPlugin extends JavaPlugin {
    private VAntiCheatCore core;
    private TrustedPlayerService trustedPlayers;
    private ClientProbeCommand clientProbeCommand;
    private CheckHacksClientDetectionModule clientDetectionModule;
    private ClientDetectionConfig clientDetectionConfig;
    private volatile boolean clientConfigValid;
    private AutomaticClientDetectionListener automaticDetectionListener;
    private Messages messages;
    private LunarClientService lunarService;
    private ClientPlatformService clientPlatforms;
    private VAntiCheatDiagnostics diagnostics;
    private final AtomicLong registryVersion = new AtomicLong();
    private final AtomicReference<VAntiCheatDiagnostics.Reload> lastReload = new AtomicReference<>();

    @Override
    public void onLoad() {
        getLogger().info("VAntiCheat loading");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("client-detection.yml");
        saveResourceIfMissing("messages.yml");
        messages = Messages.load(getDataFolder().toPath(), getLogger());
        FoundationConfig config = ConfigurationLoader.load(getDataFolder().toPath(), getLogger());
        EnforcementConfig enforcementConfig = ConfigurationLoader.loadEnforcement(getDataFolder().toPath(), getLogger());
        trustedPlayers = new PersistentTrustedPlayerService(getDataFolder().toPath()
                .resolve("data").resolve("trusted-players.yml"), getLogger());
        trustedPlayers.load();
        if (!config.enabled()) {
            getLogger().info("VAntiCheat is disabled");
            return;
        }
        var platform = PlatformDetector.detect(getServer());
        var scheduler = new PaperFoliaScheduler(this, getServer(), platform);
        clientPlatforms = PaperClientPlatformProviders.create();
        new ClientPlatformListener(this, clientPlatforms, getLogger());
        getLogger().info("Client platform providers=" + clientPlatforms.providers()
                + " readiness=" + clientPlatforms.readiness());
        core = new VAntiCheatCore(config, new PlatformContext(platform, scheduler), getLogger());
        EnforcementService enforcement = new EnforcementService(
                new DefaultEnforcementPolicy(enforcementConfig.enabled()),
                new PaperEnforcementExecutor(), messages.render("kick.confirmed"), getLogger(), trustedPlayers);
        startLunar(scheduler);
        clientDetectionConfig = loadClientDetectionConfig();
        if (clientDetectionConfig.enabled() && config.detectionEnabled()) {
            PaperSignProbeTransport transport = new PaperSignProbeTransport(this, scheduler,
                    clientDetectionConfig.timeoutTicks(), config.debug());
            transport.setProbeEligibility(id -> clientPlatforms.refresh(id).canProbe());
            clientDetectionModule = new CheckHacksClientDetectionModule(clientDetectionConfig, transport);
            clientDetectionModule.setProbeEligibility(id -> clientPlatforms.refresh(id).canProbe());
            clientPlatforms.addListener((id, classification) -> {
                if (classification.state() == ClientPlatformService.State.BEDROCK && clientDetectionModule != null) {
                    if (automaticDetectionListener != null) automaticDetectionListener.platformBecameBedrock(id);
                    else clientDetectionModule.disconnect(id);
                }
            });
            core.detectionRegistry().register(clientDetectionModule);
            clientProbeCommand = new ClientProbeCommand(clientDetectionModule, enforcement, getLogger(), messages,
                    clientPlatforms, scheduler);
            getCommand("vacprobe").setExecutor(clientProbeCommand);
            automaticDetectionListener = new AutomaticClientDetectionListener(this, scheduler, clientDetectionModule,
                    clientDetectionConfig, enforcement, getLogger(), clientPlatforms);
            getLogger().info("clientDetection=READY probes=" + clientDetectionConfig.probes().size());
        } else {
            String reason = !config.detectionEnabled() ? "DISABLED_BY_CONFIG"
                    : !clientConfigValid ? "INVALID_CONFIG" : "DISABLED_BY_CLIENT_CONFIG";
            getLogger().warning("clientDetection=UNAVAILABLE reason=" + reason);
        }
        core.start();
        long currentRegistryVersion = clientConfigValid
                ? registryVersion.updateAndGet(current -> current == 0 ? 1 : current) : registryVersion.get();
        lastReload.set(new VAntiCheatDiagnostics.Reload(
                clientDetectionModule == null ? "STARTUP_UNAVAILABLE" : "STARTUP",
                Instant.now(), clientDetectionModule == null ? "Client detection is unavailable" : "Initial configuration",
                currentRegistryVersion, clientDetectionModule != null));
        diagnostics = new VAntiCheatDiagnostics(getPluginMeta().getVersion(), platform.toString(), core,
                () -> clientDetectionConfig, () -> clientConfigValid, clientDetectionModule,
                () -> automaticDetectionListener == null ? null : automaticDetectionListener.diagnostics(),
                clientPlatforms, lunarService, trustedPlayers, registryVersion::get, lastReload);
        VAntiCheatCommand adminCommand = new VAntiCheatCommand(trustedPlayers, this::reloadPlugin,
                clientProbeCommand, lunarService, messages, diagnostics);
        getCommand("vac").setExecutor(adminCommand);
        getCommand("vac").setTabCompleter(adminCommand);
        getLogger().info("VAntiCheat enabled; version=" + getPluginMeta().getVersion()
                + " platform=" + platform + " debug=" + config.debug());
    }

    @Override
    public void onDisable() {
        if (getCommand("vac") != null) {
            getCommand("vac").setExecutor(null);
            getCommand("vac").setTabCompleter(null);
        }
        if (getCommand("vacprobe") != null) getCommand("vacprobe").setExecutor(null);
        if (lunarService != null) {
            lunarService.stop();
            lunarService = null;
        }
        if (automaticDetectionListener != null) automaticDetectionListener.stop();
        if (clientPlatforms != null) {
            clientPlatforms.clear();
            clientPlatforms = null;
        }
        if (core != null) {
            core.shutdown();
            getLogger().info("VAntiCheat disabled");
        }
        if (trustedPlayers != null) {
            trustedPlayers.save();
            trustedPlayers = null;
        }
        clientProbeCommand = null;
        clientDetectionModule = null;
        clientDetectionConfig = null;
        automaticDetectionListener = null;
        messages = null;
        diagnostics = null;
    }

    public boolean reloadPlugin() {
        ClientDetectionConfig replacement;
        try {
            replacement = ClientDetectionConfig.loadStrict(getDataFolder().toPath());
        } catch (java.io.IOException | RuntimeException exception) {
            recordReload("FAILED", exception.getMessage(), clientDetectionModule != null);
            getLogger().warning("Reload rejected; last known-good configuration remains active: "
                    + exception.getMessage());
            return false;
        }
        if (clientDetectionModule == null || automaticDetectionListener == null) {
            recordReload("FAILED", "Client detection is unavailable", false);
            getLogger().warning("Reload rejected; client detection is unavailable. Restart the plugin after fixing its startup configuration.");
            return false;
        }
        clientDetectionModule.replaceConfiguration(replacement);
        clientDetectionConfig = replacement;
        clientConfigValid = true;
        automaticDetectionListener.reloadConfiguration(replacement);
        replacement.warnings().forEach(getLogger()::warning);
        long version = registryVersion.incrementAndGet();
        lastReload.set(new VAntiCheatDiagnostics.Reload("SUCCESS", Instant.now(),
                "Probe registry replaced", version, true));
        getLogger().info("Client detection registry reloaded atomically probes=" + replacement.probes().size());
        return true;
    }

    private void recordReload(String state, String message, boolean lastKnownGoodActive) {
        String summary = message == null || message.isBlank() ? "Configuration rejected" : message;
        String[] lines = summary.split("\\R");
        if (lines.length > 1) summary = String.join(" ", java.util.Arrays.copyOf(lines,
                Math.min(lines.length, 4)));
        if (summary.length() > 120) summary = summary.substring(0, 117) + "...";
        lastReload.set(new VAntiCheatDiagnostics.Reload(state, Instant.now(), summary,
                registryVersion.get(), lastKnownGoodActive));
    }

    private ClientDetectionConfig loadClientDetectionConfig() {
        try {
            ClientDetectionConfig loaded = ClientDetectionConfig.loadStrict(getDataFolder().toPath());
            loaded.warnings().forEach(getLogger()::warning);
            clientConfigValid = true;
            return loaded;
        } catch (java.io.IOException | RuntimeException exception) {
            clientConfigValid = false;
            getLogger().warning("Unable to load client-detection.yml; client detection is unavailable: "
                    + exception.getMessage());
            return ClientDetectionConfig.disabled();
        }
    }

    private void saveResourceIfMissing(String resource) {
        if (!getDataFolder().toPath().resolve(resource).toFile().isFile()) {
            saveResource(resource, false);
        }
    }

    private void startLunar(PaperFoliaScheduler scheduler) {
        LunarPolicyConfig lunarConfig = LunarPolicyConfig.load(getDataFolder().toPath(), getLogger());
        lunarService = new LunarClientService(getLogger(), scheduler);
        LunarClientIntegration bridge = lunarConfig.enabled()
                ? ApolloBridgeLoader.load(getLogger(), lunarService::handleRegistration,
                lunarService::handleUnregister)
                : null;
        lunarService.start(lunarConfig, bridge);
        if (lunarConfig.enabled()) {
            getServer().getPluginManager().registerEvents(new LunarQuitListener(lunarService), this);
            getServer().getPluginManager().registerEvents(new ApolloPluginLifecycleListener(lunarService), this);
        }
    }

    public VAntiCheatCore core() {
        return core;
    }
}
