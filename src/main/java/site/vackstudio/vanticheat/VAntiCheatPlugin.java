package site.vackstudio.vanticheat;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.HandlerList;
import site.vackstudio.vanticheat.config.ConfigurationLoader;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.config.EnforcementConfig;
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
import site.vackstudio.vanticheat.config.BehaviorDetectionConfig;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorRegistry;
import site.vackstudio.vanticheat.detection.behavior.ReachBehaviorModule;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.combat.CombatEvidence;
import site.vackstudio.vanticheat.detection.behavior.combat.KillAuraDetector;
import site.vackstudio.vanticheat.detection.behavior.combat.autoclicker.AutoClickerDetector;
import site.vackstudio.vanticheat.platform.paper.PaperBehaviorObservationListener;
import site.vackstudio.vanticheat.detection.behavior.movement.FlyDetector;
import site.vackstudio.vanticheat.detection.behavior.movement.NoFallDetector;
import site.vackstudio.vanticheat.detection.behavior.movement.SpeedDetector;
import site.vackstudio.vanticheat.detection.behavior.placement.ScaffoldDetector;
import site.vackstudio.vanticheat.platform.paper.TrustedPlayerCommand;
import site.vackstudio.vanticheat.trusted.PersistentTrustedPlayerService;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;

public final class VAntiCheatPlugin extends JavaPlugin {
    private VAntiCheatCore core;
    private BehaviorRegistry behaviorRegistry;
    private TrustedPlayerService trustedPlayers;

    @Override
    public void onLoad() {
        getLogger().info("VAntiCheat loading");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("client-detection.yml", false);
        saveResource("behavior-detection.yml", false);
        FoundationConfig config = ConfigurationLoader.load(getDataFolder().toPath(), getLogger());
        EnforcementConfig enforcementConfig = ConfigurationLoader.loadEnforcement(getDataFolder().toPath(), getLogger());
        BehaviorDetectionConfig behaviorConfig = BehaviorDetectionConfig.load(getDataFolder().toPath(), getLogger());
        trustedPlayers = new PersistentTrustedPlayerService(getDataFolder().toPath()
                .resolve("data").resolve("trusted-players.yml"), getLogger());
        trustedPlayers.load();
        if (!config.enabled()) {
            getLogger().info("VAntiCheat is disabled");
            return;
        }
        var platform = PlatformDetector.detect(getServer());
        var scheduler = new PaperFoliaScheduler(this, getServer(), platform);
        core = new VAntiCheatCore(config, new PlatformContext(platform, scheduler), getLogger());
        EnforcementService enforcement = new EnforcementService(
                new DefaultEnforcementPolicy(enforcementConfig.enabled()),
                new PaperEnforcementExecutor(), enforcementConfig.confirmedDetectionMessage(), getLogger(), trustedPlayers);
        TrustedPlayerCommand trustedCommand = new TrustedPlayerCommand(trustedPlayers, this::reloadPlugin);
        getCommand("vac").setExecutor(trustedCommand);
        getCommand("vac").setTabCompleter(trustedCommand);
        ClientDetectionConfig clientDetection = ClientDetectionConfig.load(getDataFolder().toPath(), getLogger());
        if (clientDetection.enabled() && config.detectionEnabled()) {
            var module = new CheckHacksClientDetectionModule(clientDetection,
                    new PaperSignProbeTransport(this, scheduler, clientDetection.timeoutTicks(), config.debug()));
            core.detectionRegistry().register(module);
            getCommand("vacprobe").setExecutor(new ClientProbeCommand(module, enforcement, getLogger()));
            new AutomaticClientDetectionListener(this, scheduler, module, clientDetection, enforcement, getLogger());
        }
        core.start();
        if (behaviorConfig.enabled()) {
            behaviorRegistry = new BehaviorRegistry();
            behaviorRegistry.register(new ReachBehaviorModule(behaviorConfig.reachEnabled()));
            if (behaviorConfig.combatEnabled()) {
                behaviorRegistry.register(new KillAuraDetector(behaviorConfig.killauraEnabled()));
                behaviorRegistry.register(new AutoClickerDetector(behaviorConfig.autoclickerEnabled()));
            }
            if (behaviorConfig.movementEnabled()) {
                behaviorRegistry.register(new FlyDetector(behaviorConfig.flyEnabled()));
                behaviorRegistry.register(new NoFallDetector(behaviorConfig.noFallEnabled()));
                behaviorRegistry.register(new SpeedDetector(behaviorConfig.speedEnabled()));
                behaviorRegistry.register(new ScaffoldDetector(behaviorConfig.scaffoldEnabled()));
            }
            behaviorRegistry.start(new BehaviorModuleContext(getLogger(), signal -> {
                if (signal.level() != SignalLevel.CLEAR) {
                    getLogger().fine("Behavior signal detector=" + signal.detectorId()
                            + " player=" + signal.playerId() + " level=" + signal.level()
                            + " reason=" + signal.reason() + " evidence=" + signal.evidence());
                }
            }, evidence -> {
                if (evidence.confidence().ordinal() >= 2) {
                    getLogger().fine("Combat evidence detector=" + evidence.detector()
                            + " player=" + evidence.attackerId() + " confidence=" + evidence.confidence()
                            + " signals=" + evidence.signalTypes() + " context=" + evidence.context());
                }
             }, evidence -> {
                 if (evidence.confidence().ordinal() >= 2) {
                     getLogger().fine("AutoClicker evidence detector=" + evidence.detector()
                             + " player=" + evidence.playerId() + " confidence=" + evidence.confidence()
                             + " signals=" + evidence.signalTypes() + " context=" + evidence.context());
                 }
             }, evidence -> {
                 if (evidence.confidence().ordinal() >= 2) {
                     getLogger().fine("Movement evidence detector=" + evidence.detector()
                            + " player=" + evidence.playerId() + " confidence=" + evidence.confidence()
                            + " signals=" + evidence.signalTypes() + " context=" + evidence.context());
                }
            }, evidence -> {
                if (evidence.confidence().ordinal() >= 2) {
                    getLogger().fine("NoFall evidence detector=" + evidence.detector()
                            + " player=" + evidence.playerId() + " confidence=" + evidence.confidence()
                            + " signals=" + evidence.signalTypes() + " context=" + evidence.context());
                }
             }, evidence -> {
                 if (evidence.confidence().ordinal() >= 2) {
                     getLogger().fine("Speed evidence detector=" + evidence.detector()
                             + " player=" + evidence.playerId() + " confidence=" + evidence.confidence()
                             + " signals=" + evidence.signalTypes() + " context=" + evidence.context());
                 }
             }, evidence -> {
                 if (evidence.confidence().ordinal() >= 2) {
                     getLogger().fine("Scaffold evidence detector=" + evidence.detector()
                             + " player=" + evidence.playerId() + " confidence=" + evidence.confidence()
                             + " signals=" + evidence.signalTypes() + " context=" + evidence.context());
                 }
             }));
            new PaperBehaviorObservationListener(this, behaviorRegistry, getLogger());
        }
        getLogger().info("VAntiCheat enabled; version=" + getPluginMeta().getVersion()
                + " platform=" + platform + " debug=" + config.debug());
    }

    @Override
    public void onDisable() {
        if (behaviorRegistry != null) {
            behaviorRegistry.stop();
            behaviorRegistry = null;
        }
        if (core != null) {
            core.shutdown();
            getLogger().info("VAntiCheat disabled");
        }
        if (trustedPlayers != null) {
            trustedPlayers.save();
            trustedPlayers = null;
        }
    }

    public void reloadPlugin() {
        HandlerList.unregisterAll(this);
        onDisable();
        reloadConfig();
        onEnable();
    }

    public VAntiCheatCore core() {
        return core;
    }
}
