package com.hexa.vanticheat.core;

import com.hexa.vanticheat.alerts.AlertManager;
import com.hexa.vanticheat.antiesp.AntiESPManager;
import com.hexa.vanticheat.antixray.MiningAnalyzer;
import com.hexa.vanticheat.antixray.OreExposureTracker;
import com.hexa.vanticheat.antixray.XrayConfidenceEngine;
import com.hexa.vanticheat.antixray.XrayListener;
import com.hexa.vanticheat.api.VAntiCheatAPI;import com.hexa.vanticheat.checks.CheckManager;
import com.hexa.vanticheat.checks.ViolationManager;
import com.hexa.vanticheat.client.AntiSpoof;
import com.hexa.vanticheat.client.ClientDetector;
import com.hexa.vanticheat.client.ForbiddenModRegistry;
import com.hexa.vanticheat.client.JoinSecurityManager;
import com.hexa.vanticheat.client.ModDetector;
import com.hexa.vanticheat.commands.VACCommand;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.FileEvidenceStorage;
import com.hexa.vanticheat.evidence.ReplayBuffer;
import com.hexa.vanticheat.evidence.ViolationHistory;
import com.hexa.vanticheat.integration.PacketAdapter;
import com.hexa.vanticheat.integration.ViaVersionAdapter;
import com.hexa.vanticheat.platform.ServerPlatform;
import com.hexa.vanticheat.product.ConfigMigration;
import com.hexa.vanticheat.product.Diagnostics;
import com.hexa.vanticheat.product.FeatureFlags;
import com.hexa.vanticheat.product.LicenseProvider;
import com.hexa.vanticheat.simulation.InvestigationTracker;
import com.hexa.vanticheat.simulation.WorldReplica;
import com.hexa.vanticheat.version.ServerProfile;
import com.hexa.vanticheat.punishment.PunishmentManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * VAntiCheat bootstrap for Folia 1.21.11 + Java 21.
 *
 * <p>Folia rules followed strictly:
 * <ul>
 *   <li>No global Bukkit scheduler for entity/world access.</li>
 *   <li>Region-owned work via {@link VTaskManager#region(java.util.function.Consumer)}.</li>
 *   <li>Entity-owned work via {@link VTaskManager#entity(org.bukkit.entity.Entity, java.util.function.Consumer)}.</li>
 *   <li>IO via AsyncScheduler.</li>
 * </ul>
 */
public final class VAntiCheat extends JavaPlugin {

    private static VAntiCheat instance;
    private static VAntiCheatAPI api;

    private VConfig config;
    private VLogger vLogger;
    private Messages messages;
    private VTaskManager tasks;
    private VProfiler profiler;

    private EvidenceManager evidenceManager;
    private ConfidenceEngine confidenceEngine;
    private ViolationHistory violationHistory;
    private ViolationManager violationManager;
    private PunishmentManager punishmentManager;
    private AlertManager alertManager;

    private ClientDetector clientDetector;
    private ModDetector modDetector;
    private AntiSpoof antiSpoof;
    private JoinSecurityManager joinSecurity;
    private ForbiddenModRegistry modRegistry;

    private OreExposureTracker oreExposureTracker;
    private MiningAnalyzer miningAnalyzer;
    private XrayConfidenceEngine xrayConfidence;
    private AntiESPManager antiESPManager;
    private CheckManager checkManager;
    private TrustManager trustManager;
    private TpsMeter tpsMeter;
    private ReplayBuffer replayBuffer;
    private ServerPlatform platform;
    private ServerProfile serverProfile;
    private ViaVersionAdapter viaVersion;
    private PacketAdapter packetAdapter;
    private LicenseProvider license;
    private FeatureFlags features;
    private InvestigationTracker investigations;
    private WorldReplica worldReplica;
    private boolean listenersRegistered;

    public static VAntiCheat getInstance() {
        return instance;
    }

    public static VAntiCheatAPI api() {
        return api;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.vLogger = new VLogger(this);
        this.config = new VConfig(this, vLogger);
        this.messages = new Messages(this);
        this.tasks = new VTaskManager(this);
        this.profiler = new VProfiler();
        this.tpsMeter = new TpsMeter();
        this.replayBuffer = new ReplayBuffer(10);
        config.rebuildSnapshot(); // freeze hot-path config once
        profiler.configure(config.snapshot().profilingEnabled, config.snapshot().profilingSampleRate);
        // Product layers: platform/version/integrations/license/features/simulation.
        this.platform = ServerPlatform.detect();
        this.serverProfile = ServerProfile.detect();
        this.viaVersion = new ViaVersionAdapter();
        this.packetAdapter = new PacketAdapter();
        this.license = LicenseProvider.local();
        this.features = new FeatureFlags(config, license);
        this.investigations = new InvestigationTracker();
        this.worldReplica = new WorldReplica();
        for (String n : ConfigMigration.migrate(this)) vLogger.info(n);

        // Phase 2: evidence / confidence / violations / punishment / alerts
        this.violationHistory = new ViolationHistory();
        FileEvidenceStorage storage = new FileEvidenceStorage(this, vLogger, tasks);
        this.evidenceManager = new EvidenceManager(this, vLogger, tasks, storage);
        this.confidenceEngine = new ConfidenceEngine(config);
        this.violationManager = new ViolationManager(this, config);
        this.alertManager = new AlertManager(this, config);
        this.punishmentManager = new PunishmentManager(this, config, vLogger, tasks, evidenceManager, alertManager);

        // Wire punishment decisions from evidence: evidence manager notifies punishment manager.
        this.evidenceManager.setPunishmentHook(punishmentManager::onEvidence);

        // Phase 3/4: client security
        this.modRegistry = new ForbiddenModRegistry(config);
        this.clientDetector = new ClientDetector(this, config, vLogger, evidenceManager, confidenceEngine, tasks);
        this.modDetector = new ModDetector(this, config, vLogger, evidenceManager, confidenceEngine, modRegistry);
        this.antiSpoof = new AntiSpoof(config, evidenceManager, confidenceEngine);
        this.joinSecurity = new JoinSecurityManager(this, config, vLogger, tasks, clientDetector, modDetector, antiSpoof, punishmentManager, evidenceManager);

        // Phase 5: anti-xray
        this.oreExposureTracker = new OreExposureTracker(config);
        this.miningAnalyzer = new MiningAnalyzer(config);
        this.xrayConfidence = new XrayConfidenceEngine(config, evidenceManager, confidenceEngine);
        XrayListener xrayListener = new XrayListener(this, config, oreExposureTracker, miningAnalyzer, xrayConfidence, tasks, profiler);

        // Phase 6: anti-esp
        this.antiESPManager = new AntiESPManager(this, config, vLogger, evidenceManager, confidenceEngine, tasks, profiler);

        // Phase 7: behavior checks
        this.checkManager = new CheckManager(this, config, vLogger, tasks, profiler,
                evidenceManager, confidenceEngine, violationManager, alertManager, punishmentManager);

        // Listeners (all event-driven; no per-tick global scans). Registered once; reload never duplicates.
        if (!listenersRegistered) {
            getServer().getPluginManager().registerEvents(clientDetector, this);
            getServer().getPluginManager().registerEvents(modDetector, this);
            getServer().getPluginManager().registerEvents(joinSecurity, this);
            getServer().getPluginManager().registerEvents(xrayListener, this);
            getServer().getPluginManager().registerEvents(antiESPManager, this);
            getServer().getPluginManager().registerEvents(checkManager, this);
            listenersRegistered = true;
        }

        // Incoming plugin-channel (client brand) listener. Modern id required;
        // legacy id best-effort only (absent on modern servers — not a warning).
        try {
            getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", clientDetector);
        } catch (Exception ex) {
            vLogger.warn("Could not register minecraft:brand channel: " + ex.getMessage());
        }
        try {
            getServer().getMessenger().registerIncomingPluginChannel(this, "MC|Brand", clientDetector);
        } catch (Exception ex) {
            vLogger.debug("Legacy MC|Brand channel unavailable (expected on modern servers).");
        }

        // Commands
        VACCommand vac = new VACCommand(this, config, evidenceManager, confidenceEngine, violationHistory,
                violationManager, punishmentManager, alertManager, clientDetector, modDetector,
                oreExposureTracker, miningAnalyzer, profiler, tasks);
        Objects.requireNonNull(getCommand("vac"), "command 'vac' missing from plugin.yml").setExecutor(vac);
        Objects.requireNonNull(getCommand("vac")).setTabCompleter(vac);

        // Periodic maintenance on AsyncScheduler (decay, persistence flush, TPS sample). Never touches world state.
        tasks.runAsyncTimer(this::maintenance, 20L * 5, 20L * 5);
        tasks.runAsyncTimer(() -> { try { tpsMeter.sample(); } catch (Throwable ignored) {} }, 20L, 20L);

        api = new VAntiCheatAPI(this, clientDetector, modDetector, confidenceEngine, evidenceManager, violationHistory);
        this.trustManager = new TrustManager(this);
        api.setTrustManager(trustManager);

        config.validate(vLogger);
        startupDiagnostics();
    }

    /** Safe reload (§23): config only, preserve evidence/trust, no listener leaks. */
    public void safeReload() {
        reloadConfig();
        try { messages.reload(); } catch (Throwable ignored) {}
        try { modRegistry().reload(); } catch (Throwable t) { vLogger.debug("registry reload: " + t.getMessage()); }
        try { config.rebuildSnapshot(); } catch (Throwable ignored) {}
        try { profiler.configure(config.snapshot().profilingEnabled, config.snapshot().profilingSampleRate); } catch (Throwable ignored) {}
        config.validate(vLogger);
        vLogger.info("Reloaded safely (evidence + trust preserved).");
    }

    /** Startup diagnostics (§28). GLOBAL thread only. */
    private void startupDiagnostics() {
        String profile = "standard";
        try { profile = config.profile().name(); } catch (Throwable ignored) {}
        vLogger.info("VAntiCheat " + getDescription().getVersion());
        vLogger.info("Platform: Folia | Minecraft: 1.21.11 | Java: " + Runtime.version().feature());
        vLogger.info("Client security: " + onOff("client_security.enabled", true)
                + " | Anti-Xray behavioral layer: " + onOff("antixray.enabled", true)
                + " | Anti-ESP: " + onOff("anti_esp.enabled", true)
                + " | Behavior checks: " + onOff("behavior.enabled", true));
        vLogger.info("Storage: JSONL | Security profile: " + profile);
        // Paper Anti-Xray reminder (behavioral layer only; engine protection is Paper's).
        vLogger.info("Note: enable Paper anti-xray in paper-global.yml (engine protection); VAntiCheat provides statistics.");
        try {
            vLogger.info(Diagnostics.status(this, platform, serverProfile, viaVersion, packetAdapter));
        } catch (Throwable ignored) {}
    }

    private String onOff(String path, boolean def) {
        try { return config.getBoolean(path, def) ? "ENABLED" : "DISABLED"; }
        catch (Throwable t) { return "UNKNOWN"; }
    }

    private void maintenance() {
        try {
            violationManager.decayAll();
            miningAnalyzer.decay();
            antiESPManager.decay();
            evidenceManager.flushIfNeeded(false);
        } catch (Throwable t) {
            vLogger.debug("maintenance error: " + t.getMessage());
        }
    }

    @Override
    public void onDisable() {
        try {
            if (evidenceManager != null) evidenceManager.flushIfNeeded(true);
            if (tasks != null) tasks.shutdown();
        } catch (Exception ignored) {
        }
        instance = null;
    }

    // ---- getters ----
    public VConfig vacConfig() { return config; }
    public VLogger vlog() { return vLogger; }
    public Messages messages() { return messages; }
    public VTaskManager tasks() { return tasks; }
    public VProfiler profiler() { return profiler; }
    public EvidenceManager evidence() { return evidenceManager; }
    public ConfidenceEngine confidence() { return confidenceEngine; }
    public ViolationHistory violationHistory() { return violationHistory; }
    public ViolationManager violations() { return violationManager; }
    public PunishmentManager punishments() { return punishmentManager; }
    public AlertManager alerts() { return alertManager; }
    public ClientDetector clients() { return clientDetector; }
    public ModDetector mods() { return modDetector; }
    public AntiSpoof spoof() { return antiSpoof; }
    public ForbiddenModRegistry modRegistry() { return modRegistry; }
    public OreExposureTracker oreTracker() { return oreExposureTracker; }
    public MiningAnalyzer mining() { return miningAnalyzer; }
    public AntiESPManager esp() { return antiESPManager; }
    public CheckManager checks() { return checkManager; }
    public TrustManager trust() { return trustManager; }
    public TpsMeter tps() { return tpsMeter; }
    public ReplayBuffer replay() { return replayBuffer; }
    public ServerPlatform platform() { return platform; }
    public ServerProfile serverProfile() { return serverProfile; }
    public ViaVersionAdapter via() { return viaVersion; }
    public PacketAdapter packets() { return packetAdapter; }
    public FeatureFlags features() { return features; }
    public InvestigationTracker investigations() { return investigations; }
    public WorldReplica replica() { return worldReplica; }
}
