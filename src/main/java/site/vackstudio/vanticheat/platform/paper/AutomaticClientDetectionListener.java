package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionSession;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.enforcement.EnforcementOutcome;
import site.vackstudio.vanticheat.enforcement.EnforcementService;
import site.vackstudio.vanticheat.enforcement.EnforcementTarget;
import site.vackstudio.vanticheat.platform.AutomaticCheckCoordinator;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Scheduler;

import java.util.logging.Logger;

public final class AutomaticClientDetectionListener implements Listener {
    private final CheckHacksClientDetectionModule module;
    private final ClientDetectionConfig configuration;
    private final EnforcementService enforcement;
    private final AutomaticCheckCoordinator coordinator;
    private final Logger logger;

    public AutomaticClientDetectionListener(Plugin plugin, Scheduler scheduler,
                                             CheckHacksClientDetectionModule module,
                                             ClientDetectionConfig configuration,
                                             EnforcementService enforcement, Logger logger) {
        this.module = module;
        this.configuration = configuration;
        this.enforcement = enforcement;
        this.logger = logger;
        this.coordinator = new AutomaticCheckCoordinator(scheduler, configuration.autoCheckDelayTicks(),
                configuration.firstJoinOnly(), configuration.maxConcurrentAutoChecks(), logger);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!configuration.autoCheckOnJoin()) return;
        Player player = event.getPlayer();
        AutomaticCheckCoordinator.Target target = new AutomaticCheckCoordinator.Target(
                player.getUniqueId(), player.getName(), player.isOnline(), !player.hasPlayedBefore(), player);
        boolean scheduled = coordinator.schedule(target, () -> start(target, player));
        if (scheduled) logger.info("Automatic client check scheduled: " + player.getName());
    }

    private void start(AutomaticCheckCoordinator.Target target, Player player) {
        if (!player.isOnline()) {
            coordinator.complete(target.id());
            logger.info("AutoCheck COMPLETE player=" + target.name() + " result=SKIPPED reason=offline-before-start");
            return;
        }
        if (module.isActive(target.id())) {
            coordinator.complete(target.id());
            logger.info("AutoCheck COMPLETE player=" + target.name() + " result=SKIPPED reason=session-already-active");
            return;
        }
        DetectionSession session = module.checkIfIdle(
                new DetectionTarget(target.id(), target.name(), true, player),
                configuration.automaticProbes(), "JOIN", result -> finish(target, player, result));
        if (session == null) {
            coordinator.complete(target.id());
            logger.info("AutoCheck COMPLETE player=" + target.name() + " result=SKIPPED reason=duplicate-or-busy");
        } else {
            logger.info("AutoCheck START player=" + target.name() + " session=" + session.sessionId());
        }
    }

    private void finish(AutomaticCheckCoordinator.Target target, Player player, DetectionResult result) {
        EnforcementOutcome outcome = enforcement.enforce(
                new EnforcementTarget(target.id(), target.name(), player.isOnline(), player), result);
        coordinator.complete(target.id());
        String mods = detectedMods(result);
        String session = sessionId(result);
        logger.info("AutoCheck RESULT player=" + target.name() + " session=" + session
                + " result=" + result.status() + " mods=" + mods);
        logger.info("AutoCheck ENFORCEMENT player=" + target.name() + " session=" + session
                + " action=" + outcome.decision().action() + " reason=" + outcome.decision().reason());
        logger.info("AutoCheck COMPLETE player=" + target.name() + " session=" + session);
    }

    static String sessionId(DetectionResult result) {
        return result.evidence().stream()
                .map(item -> item.metadata().get("session"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("unknown");
    }

    private String detectedMods(DetectionResult result) {
        java.util.List<String> mods = CheckHacksClientDetectionModule.detectedProbeIds(result).stream()
                .map(id -> configuration.probes().stream()
                        .filter(probe -> probe.id().equals(id))
                        .map(site.vackstudio.vanticheat.detection.probe.ProbeDefinition::displayName)
                        .findFirst().orElse(id))
                .toList();
        return mods.isEmpty() ? "none" : String.join(", ", mods);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        module.disconnect(event.getPlayer().getUniqueId());
        coordinator.disconnect(event.getPlayer().getUniqueId());
    }

    public int activeChecks() { return coordinator.activeCount(); }

    public void stop() { coordinator.stop(); }
}
