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
        if (!player.isOnline() || module.isActive(target.id())) {
            coordinator.complete(target.id());
            return;
        }
        DetectionSession session = module.checkIfIdle(
                new DetectionTarget(target.id(), target.name(), true, player),
                configuration.automaticProbes(), "JOIN", result -> finish(target, player, result));
        if (session == null) coordinator.complete(target.id());
        else logger.info("Automatic client check started: " + target.name());
    }

    private void finish(AutomaticCheckCoordinator.Target target, Player player, DetectionResult result) {
        EnforcementOutcome outcome = enforcement.enforce(
                new EnforcementTarget(target.id(), target.name(), player.isOnline(), player), result);
        coordinator.complete(target.id());
        String mods = detectedMods(result);
        logger.info("Automatic client check result player=" + target.name() + " status=" + result.status()
                + " action=" + outcome.decision().action() + " mods=" + mods);
        if (outcome.decision().action().name().equals("KICK")) {
            logger.info("Enforcement: KICK " + target.name() + " mods=" + mods);
        }
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
