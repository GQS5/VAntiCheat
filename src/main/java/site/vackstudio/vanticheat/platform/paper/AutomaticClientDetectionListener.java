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
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.enforcement.EnforcementOutcome;
import site.vackstudio.vanticheat.enforcement.EnforcementService;
import site.vackstudio.vanticheat.enforcement.EnforcementTarget;
import site.vackstudio.vanticheat.platform.AutomaticCheckCoordinator;
import site.vackstudio.vanticheat.platform.ClientPlatformService;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;
import java.util.logging.Logger;

/** Owns only join-trigger policy/admission; every scan runs through the shared probe engine. */
public final class AutomaticClientDetectionListener implements Listener {
    private static final int MAX_PLATFORM_ATTEMPTS = 3;
    private static final long PLATFORM_RETRY_TICKS = 2;

    private final CheckHacksClientDetectionModule module;
    private volatile ClientDetectionConfig configuration;
    private final EnforcementService enforcement;
    private final AutomaticCheckCoordinator coordinator;
    private final Logger logger;
    private final ClientPlatformService platforms;
    private final Scheduler scheduler;
    private final ConcurrentMap<UUID, JoinAttempt> joins = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Player> connections = new ConcurrentHashMap<>();
    private final Set<UUID> seenJoinEvents = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    public AutomaticClientDetectionListener(Plugin plugin, Scheduler scheduler,
                                             CheckHacksClientDetectionModule module,
                                             ClientDetectionConfig configuration,
                                             EnforcementService enforcement, Logger logger,
                                             ClientPlatformService platforms) {
        this(scheduler, module, configuration, enforcement, logger, platforms);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    AutomaticClientDetectionListener(Scheduler scheduler, CheckHacksClientDetectionModule module,
                                     ClientDetectionConfig configuration, EnforcementService enforcement,
                                     Logger logger, ClientPlatformService platforms) {
        this.module = module;
        this.configuration = configuration;
        this.enforcement = enforcement;
        this.logger = logger;
        this.platforms = platforms;
        this.scheduler = scheduler;
        this.coordinator = new AutomaticCheckCoordinator(scheduler, configuration.autoCheckDelayTicks(),
                configuration.firstJoinOnly(), configuration.maxConcurrentAutoChecks(), logger);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        onJoinPlayer(event.getPlayer());
    }

    void onJoinPlayer(Player player) {
        ClientDetectionConfig current = configuration;
        if (stopped || !current.enabled() || !current.autoCheckOnJoin() || !module.isStarted()) return;
        UUID playerId = player.getUniqueId();
        Player previousConnection = connections.put(playerId, player);
        if (previousConnection == player) return;
        if (previousConnection != null) {
            JoinAttempt stale = joins.remove(playerId);
            if (stale != null) {
                cancelRetry(stale);
                if (stale.ticket != null) coordinator.complete(stale.ticket);
            }
            module.disconnect(playerId);
            platforms.remove(playerId);
            seenJoinEvents.remove(playerId);
        }
        if (!seenJoinEvents.add(playerId)) {
            logger.fine("AutoCheck SKIPPED player=" + player.getName()
                    + " uuid=" + playerId + " reason=duplicate-join-event");
            return;
        }
        JoinAttempt attempt = new JoinAttempt(playerId, !player.hasPlayedBefore());
        if (joins.putIfAbsent(playerId, attempt) != null) {
            logger.fine("AutoCheck SKIPPED player=" + player.getName()
                    + " uuid=" + playerId + " reason=duplicate-join-event");
            return;
        }
        classifyJoin(player, attempt, 1);
    }

    private void classifyJoin(Player player, JoinAttempt attempt, int classificationAttempt) {
        if (!current(attempt)) return;
        attempt.classificationAttempts = classificationAttempt;
        cancelRetry(attempt);
        if (!player.isOnline()) {
            removeAttempt(attempt);
            coordinator.recordSkippedAdmission();
            logger.fine("AutoCheck SKIPPED player=" + player.getName()
                    + " uuid=" + attempt.playerId + " reason=offline-before-platform-classification");
            return;
        }

        ClientPlatformService.Classification classification = platforms.refresh(attempt.playerId);
        if (!current(attempt)) return;
        if (classification.state() == ClientPlatformService.State.BEDROCK) {
            removeAttempt(attempt);
            coordinator.recordSkippedAdmission();
            module.probeHealth().observePlatform(attempt.playerId, player.getName(), classification);
            logger.info("AutoCheck SKIPPED player=" + player.getName() + " uuid=" + attempt.playerId
                    + " reason=bedrock-player source=" + classification.source());
            return;
        }
        if (!classification.canProbe()) {
            if (classificationAttempt < MAX_PLATFORM_ATTEMPTS) {
                logger.fine("Platform PENDING player=" + player.getName()
                        + " uuid=" + attempt.playerId + " reason=" + classification.reason()
                        + " attempt=" + classificationAttempt);
                scheduleClassificationRetry(player, attempt, classificationAttempt + 1);
            } else {
                removeAttempt(attempt);
                coordinator.recordSkippedAdmission();
                logger.info("AutoCheck SKIPPED player=" + player.getName() + " uuid=" + attempt.playerId
                        + " reason=classification-unknown");
            }
            return;
        }
        if (enforcement.isTrusted(attempt.playerId)) {
            removeAttempt(attempt);
            coordinator.recordSkippedAdmission();
            module.probeHealth().observePlatform(attempt.playerId, player.getName(), classification);
            logger.info("AutoCheck SKIPPED player=" + player.getName() + " uuid=" + attempt.playerId
                    + " reason=trusted-player");
            return;
        }

        // Avoid even delayed coordinator admission when no automatic probe passed the
        // transport capability gate. An INTERACTIVE probe would open client UI on join.
        List<ProbeDefinition> automaticSnapshot = module.automaticEligibleProbes();
        if (automaticSnapshot.isEmpty()) {
            removeAttempt(attempt);
            coordinator.recordSkippedAdmission();
            logger.fine("AutoCheck SKIPPED player=" + player.getName()
                    + " uuid=" + attempt.playerId + " reason=no-automatic-probes"
                    + " (no probe declares a transport that is safe for automatic checks)");
            return;
        }

        // A fresh passive context per connection. Evidence from a previous connection can
        // never be seen by this one, and the automatic scan is delayed, so the client has
        // time to emit any join-time channel before the scan reads it.
        module.beginPassiveContext(attempt.playerId, classification.platform(), brandOf(player));
        AutomaticCheckCoordinator.Target target = new AutomaticCheckCoordinator.Target(
                attempt.playerId, player.getName(), player::isOnline, attempt.firstJoin, player);
        module.probeHealth().observePlatform(attempt.playerId, player.getName(), classification);
        AutomaticCheckCoordinator.Ticket ticket = coordinator.scheduleTicket(target, created -> {
            attempt.ticket = created;
            if (!current(attempt)) coordinator.complete(created);
        }, created -> start(target, player, attempt, created), created -> {
            removeAttempt(attempt);
            logger.fine("AutoCheck SKIPPED player=" + target.name() + " uuid=" + target.id()
                    + " reason=coordinator-start-failed");
        });
        if (ticket == null) {
            removeAttempt(attempt);
            logger.fine("AutoCheck SKIPPED player=" + player.getName()
                    + " uuid=" + attempt.playerId + " reason=duplicate-or-concurrency-limit");
            return;
        }
        if (current(attempt)) {
            logger.info("AutoCheck SCHEDULED player=" + player.getName()
                    + " uuid=" + attempt.playerId + " automaticProbes=" + automaticSnapshot.size());
        } else {
            coordinator.complete(ticket);
        }
    }

    /**
     * Client brand is context only and is never evidence. A platform that does not expose it,
     * or throws while doing so, degrades to an unknown brand and never affects detection.
     */
    private static String brandOf(Player player) {
        try {
            return player.getClientBrandName();
        } catch (LinkageError | RuntimeException exception) {
            return null;
        }
    }

    private void scheduleClassificationRetry(Player player, JoinAttempt attempt, int nextAttempt) {
        try {
            TaskHandle retry = scheduler.runGlobalLater(() -> {
                if (!current(attempt)) return;
                try {
                    if (player.isOnline()) {
                        scheduler.runAtEntity(new EntityTarget(player),
                                () -> classifyJoin(player, attempt, nextAttempt),
                                () -> retryRetired(attempt));
                    } else {
                        removeAttempt(attempt);
                    }
                } catch (RuntimeException exception) {
                    removeAttempt(attempt);
                    logger.warning("Platform retry scheduling failed player=" + player.getName()
                            + " reason=" + exception.getClass().getSimpleName());
                }
            }, PLATFORM_RETRY_TICKS);
            attempt.retry = retry;
            if (!current(attempt) && retry != null) retry.cancel();
        } catch (RuntimeException exception) {
            removeAttempt(attempt);
            logger.warning("Platform retry scheduling failed player=" + player.getName()
                    + " reason=" + exception.getClass().getSimpleName());
        }
    }

    private void retryRetired(JoinAttempt attempt) {
        if (joins.remove(attempt.playerId, attempt)) {
            cancelRetry(attempt);
            coordinator.recordSkippedAdmission();
            logger.fine("AutoCheck SKIPPED uuid=" + attempt.playerId
                    + " reason=entity-region-retired-during-platform-retry");
        }
    }

    private void start(AutomaticCheckCoordinator.Target target, Player player, JoinAttempt attempt,
                       AutomaticCheckCoordinator.Ticket ticket) {
        if (!current(attempt) || !coordinator.isCurrent(ticket)) {
            coordinator.complete(ticket);
            return;
        }
        if (!player.isOnline()) {
            coordinator.recordSkippedAdmission();
            finishSkipped(target, attempt, ticket, "offline-before-scan");
            return;
        }
        ClientPlatformService.Classification classification = platforms.refresh(target.id());
        if (!current(attempt)) return;
        if (classification.state() == ClientPlatformService.State.BEDROCK) {
            platformBecameBedrock(target.id());
            return;
        }
        if (!classification.canProbe()) {
            coordinator.complete(ticket);
            attempt.ticket = null;
            classifyJoin(player, attempt, attempt.classificationAttempts + 1);
            return;
        }
        if (!configuration.enabled() || !configuration.autoCheckOnJoin() || !module.isStarted()) {
            finishSkipped(target, attempt, ticket, "automatic-check-disabled");
            return;
        }
        if (enforcement.isTrusted(target.id())) {
            finishSkipped(target, attempt, ticket, "trusted-player");
            return;
        }
        if (module.isActive(target.id())) {
            finishSkipped(target, attempt, ticket, "session-already-active");
            return;
        }

        // The engine captures one immutable ClientDetectionConfig/ProbeRegistry view here.
        DetectionSession session = module.checkAutomaticIfIdle(
                new DetectionTarget(target.id(), target.name(), true, player), "JOIN",
                result -> finish(target, player, attempt, ticket, result));
        if (session == null && coordinator.isCurrent(ticket)) {
            finishSkipped(target, attempt, ticket, "empty-registry-or-duplicate-session");
        } else if (session != null && !session.state().terminal()) {
            logger.info("AutoCheck START player=" + target.name() + " uuid=" + target.id()
                    + " session=" + session.sessionId() + " trigger=JOIN");
        }
    }

    private void finish(AutomaticCheckCoordinator.Target target, Player player, JoinAttempt attempt,
                        AutomaticCheckCoordinator.Ticket ticket, DetectionResult result) {
        if (!coordinator.isCurrent(ticket)) return;
        try {
            scheduler.runAtEntity(new EntityTarget(player),
                    () -> finishOnEntity(target, player, attempt, ticket, result),
                    () -> finishSkipped(target, attempt, ticket, "entity-region-retired-before-result"));
        } catch (RuntimeException exception) {
            finishSkipped(target, attempt, ticket, "entity-context-unavailable");
        }
    }

    private void finishOnEntity(AutomaticCheckCoordinator.Target target, Player player, JoinAttempt attempt,
                                AutomaticCheckCoordinator.Ticket ticket, DetectionResult result) {
        if (stopped || !coordinator.isCurrent(ticket)) return;
        if (!player.isOnline()) {
            finishSkipped(target, attempt, ticket, "disconnected-before-enforcement");
            return;
        }
        ClientPlatformService.Classification classification = platforms.refresh(target.id());
        if (!coordinator.isCurrent(ticket)) return;
        if (!classification.canProbe()) {
            if (classification.state() == ClientPlatformService.State.BEDROCK) platformBecameBedrock(target.id());
            else finishSkipped(target, attempt, ticket, "platform-classification-unknown");
            return;
        }
        if (!coordinator.claimCompletion(ticket)) return; // Exactly one terminal callback owns enforcement.
        if (result.status() == DetectionStatus.SKIPPED) {
            coordinator.recordSkippedAdmission();
            coordinator.complete(ticket);
            removeAttempt(attempt);
            logger.info("AutoCheck SKIPPED player=" + target.name() + " uuid=" + target.id()
                    + " reason=probe-session-skipped");
            return;
        }
        try {
            if (stopped || !coordinator.isCurrent(ticket) || !player.isOnline()) return;
            EnforcementOutcome outcome = enforcement.enforce(
                    new EnforcementTarget(target.id(), target.name(), true, player), result);
            module.enforcementComplete(result, outcome.decision().action().name());
            String mods = detectedMods(result);
            String session = sessionId(result);
            logger.info("AutoCheck RESULT player=" + target.name() + " uuid=" + target.id()
                    + " session=" + session + " result=" + result.status()
                    + " reason=" + result.reason() + " mods=" + mods);
            logger.info("AutoCheck ENFORCEMENT player=" + target.name() + " uuid=" + target.id()
                    + " session=" + session + " action=" + outcome.decision().action()
                    + " reason=" + outcome.decision().reason());
            logger.info("AutoCheck COMPLETE player=" + target.name() + " uuid=" + target.id()
                    + " session=" + session + " result=" + result.status());
        } catch (RuntimeException exception) {
            logger.warning("Automatic enforcement failed player=" + target.name()
                    + " uuid=" + target.id() + " reason=" + exception.getClass().getSimpleName());
            module.enforcementComplete(result, "ERROR");
        } finally {
            coordinator.complete(ticket);
            removeAttempt(attempt);
        }
    }

    private void finishSkipped(AutomaticCheckCoordinator.Target target, JoinAttempt attempt,
                               AutomaticCheckCoordinator.Ticket ticket, String reason) {
        if (!coordinator.complete(ticket)) return;
        coordinator.recordSkippedAdmission();
        removeAttempt(attempt);
        logger.info("AutoCheck SKIPPED player=" + target.name()
                + " uuid=" + target.id() + " reason=" + reason);
    }

    private void cancelRetry(JoinAttempt attempt) {
        TaskHandle retry = attempt.retry;
        attempt.retry = null;
        if (retry != null && !retry.cancelled()) retry.cancel();
    }

    private boolean current(JoinAttempt attempt) {
        return !stopped && joins.get(attempt.playerId) == attempt;
    }

    private void removeAttempt(JoinAttempt attempt) {
        if (joins.remove(attempt.playerId, attempt)) cancelRetry(attempt);
    }

    static String sessionId(DetectionResult result) {
        return result.evidence().stream()
                .map(item -> item.metadata().get("session"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("unknown");
    }

    private String detectedMods(DetectionResult result) {
        List<String> mods = CheckHacksClientDetectionModule.detectedProbeDisplayNames(result);
        return mods.isEmpty() ? "none" : String.join(", ", mods);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        onQuitPlayer(event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getPlayer());
    }

    void onQuitPlayer(UUID playerId, String playerName) {
        Player current = connections.get(playerId);
        onQuitPlayer(playerId, playerName, current);
    }

    void onQuitPlayer(UUID playerId, String playerName, Player quittingPlayer) {
        if (quittingPlayer != null && !connections.remove(playerId, quittingPlayer)) return;
        seenJoinEvents.remove(playerId);
        JoinAttempt attempt = joins.remove(playerId);
        if (attempt != null) {
            cancelRetry(attempt);
            if (attempt.ticket != null) coordinator.complete(attempt.ticket);
        }
        platforms.remove(playerId);
        module.signals().remove(playerId);
        module.disconnect(playerId);
        logger.fine("AutoCheck CANCELLED player=" + playerName
                + " uuid=" + playerId + " reason=disconnect");
    }

    public int activeChecks() { return coordinator.activeCount(); }

    public AutomaticCheckCoordinator.MetricsSnapshot diagnostics() { return coordinator.metrics(); }

    public void platformBecameBedrock(UUID playerId) {
        JoinAttempt attempt = joins.remove(playerId);
        if (attempt != null) {
            coordinator.recordSkippedAdmission();
            cancelRetry(attempt);
            if (attempt.ticket != null) coordinator.complete(attempt.ticket);
        }
        module.signals().remove(playerId);
        module.disconnect(playerId);
    }

    public void reloadConfiguration(ClientDetectionConfig replacement) {
        configuration = replacement;
        coordinator.reconfigure(replacement.autoCheckDelayTicks(), replacement.firstJoinOnly(),
                replacement.maxConcurrentAutoChecks());
        if (!replacement.enabled() || !replacement.autoCheckOnJoin()) {
            coordinator.cancelNotStarted();
            joins.forEach((id, attempt) -> {
                if (attempt.ticket == null || !coordinator.isStarted(attempt.ticket)) {
                    if (joins.remove(id, attempt)) {
                        cancelRetry(attempt);
                        if (attempt.ticket != null) coordinator.complete(attempt.ticket);
                    }
                }
            });
        }
    }

    public void stop() {
        stopped = true;
        joins.forEach((id, attempt) -> {
            if (joins.remove(id, attempt)) {
                cancelRetry(attempt);
                if (attempt.ticket != null) coordinator.complete(attempt.ticket);
                module.disconnect(id);
            }
        });
        connections.clear();
        seenJoinEvents.clear();
        coordinator.stop();
    }

    private static final class JoinAttempt {
        private final UUID playerId;
        private final boolean firstJoin;
        private volatile int classificationAttempts;
        private volatile TaskHandle retry;
        private volatile AutomaticCheckCoordinator.Ticket ticket;

        private JoinAttempt(UUID playerId, boolean firstJoin) {
            this.playerId = playerId;
            this.firstJoin = firstJoin;
        }
    }
}
