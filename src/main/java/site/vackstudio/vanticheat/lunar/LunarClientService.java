package site.vackstudio.vanticheat.lunar;

import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Applies the existing Lunar Minimap policy as an isolated optional side effect. */
public final class LunarClientService {
    public enum RegistrationOutcome {
        IGNORED_DISABLED,
        IGNORED_UNAVAILABLE,
        IGNORED_UNSUPPORTED,
        SCHEDULED,
        REGISTERED,
        POLICY_APPLIED,
        FAILED
    }

    public enum ActionState { NOT_RUN, SCHEDULED, APPLIED, ALREADY_APPLIED, FAILED }

    public record LunarSnapshot(LunarClientIntegration.Availability availability, Boolean lunar,
                                boolean minimapPolicyEnabled, LunarPlayerState state) { }

    private final Logger logger;
    private final Scheduler scheduler;
    private final Object lifecycleGate = new Object();
    private final ConcurrentMap<UUID, RegistrationTicket> registrations = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, LunarPlayerState> states = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> supportedPlayers = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> unsupportedPlayers = ConcurrentHashMap.newKeySet();
    private volatile LunarPolicyConfig config = LunarPolicyConfig.defaults();
    private volatile LunarClientIntegration integration;
    private volatile LunarClientIntegration.Availability availability =
            LunarClientIntegration.Availability.NOT_PRESENT;
    private volatile ActionState lastAction = ActionState.NOT_RUN;
    private volatile boolean stopped = true;
    private volatile boolean apolloSuspended;

    public LunarClientService(Logger logger, Scheduler scheduler) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public void start(LunarPolicyConfig config, LunarClientIntegration integration) {
        stop();
        synchronized (lifecycleGate) {
            this.config = Objects.requireNonNull(config, "config");
            this.integration = config.enabled() ? integration : null;
            this.stopped = false;
            this.apolloSuspended = false;
            if (!config.enabled()) {
                availability = LunarClientIntegration.Availability.DISABLED;
                logger.info("[VAntiCheat] Lunar integration: DISABLED");
                return;
            }
            if (this.integration == null) {
                availability = LunarClientIntegration.Availability.NOT_PRESENT;
                logger.info("[VAntiCheat] Lunar integration: NOT_PRESENT");
                return;
            }
            try {
                this.integration.start();
                availability = safeAvailability(this.integration);
            } catch (LinkageError | RuntimeException exception) {
                availability = LunarClientIntegration.Availability.FAILED;
                apolloSuspended = true;
                logger.log(Level.FINE, "[VAntiCheat] Lunar integration startup failed", exception);
            }
            logger.info("[VAntiCheat] Lunar integration: " + availability);
        }
    }

    public void stop() {
        synchronized (lifecycleGate) {
            stopped = true;
            apolloSuspended = true;
            for (RegistrationTicket ticket : registrations.values()) cancelPending(ticket);
            registrations.clear();
            states.clear();
            supportedPlayers.clear();
            unsupportedPlayers.clear();
            LunarClientIntegration current = integration;
            integration = null;
            availability = LunarClientIntegration.Availability.NOT_PRESENT;
            lastAction = ActionState.NOT_RUN;
            if (current != null) {
                try {
                    current.stop();
                } catch (LinkageError | RuntimeException exception) {
                    logger.log(Level.FINE, "[VAntiCheat] Lunar integration stop failed", exception);
                }
            }
        }
    }

    /** Apollo's event thread only hands off an opaque entity; all player/API work is entity-scheduled. */
    public RegistrationOutcome handleRegistration(LunarClientIntegration.Registration registration) {
        Objects.requireNonNull(registration, "registration");
        synchronized (lifecycleGate) {
            LunarClientIntegration current = integration;
            if (stopped || !config.enabled()) return RegistrationOutcome.IGNORED_DISABLED;
            if (apolloSuspended) return RegistrationOutcome.IGNORED_UNAVAILABLE;
            if (current == null) return RegistrationOutcome.IGNORED_UNAVAILABLE;

            RegistrationTicket ticket = new RegistrationTicket(registration);
            RegistrationTicket previous = registrations.put(registration.playerId(), ticket);
            if (previous != null) cancelPending(previous);
            states.remove(registration.playerId());
            supportedPlayers.remove(registration.playerId());
            unsupportedPlayers.remove(registration.playerId());
            lastAction = ActionState.SCHEDULED;
            return scheduleApplication(current, registration, ticket)
                    ? RegistrationOutcome.SCHEDULED : RegistrationOutcome.FAILED;
        }
    }

    private boolean scheduleApplication(LunarClientIntegration current,
                                       LunarClientIntegration.Registration registration,
                                       RegistrationTicket ticket) {
        try {
            TaskHandle task = scheduler.runAtEntity(new EntityTarget(registration.playerHandle()),
                    () -> applyOnPlayer(current, registration, ticket),
                    () -> fail(registration.playerId(), registration.playerName(), ticket,
                            new IllegalStateException("player entity region retired")));
            ticket.setTask(task);
            return true;
        } catch (LinkageError | RuntimeException exception) {
            fail(registration.playerId(), registration.playerName(), ticket, exception);
            return false;
        }
    }

    private void applyOnPlayer(LunarClientIntegration current,
                               LunarClientIntegration.Registration registration,
                               RegistrationTicket ticket) {
        synchronized (lifecycleGate) {
            ticket.markStarted();
            if (!isCurrent(registration.playerId(), ticket)) return;
            try {
                availability = safeAvailability(current);
                if (availability != LunarClientIntegration.Availability.AVAILABLE) {
                    if (availability == LunarClientIntegration.Availability.FAILED) {
                        fail(registration.playerId(), registration.playerName(), ticket,
                                new IllegalStateException("Apollo integration failed"));
                    } else {
                    states.remove(registration.playerId());
                    supportedPlayers.remove(registration.playerId());
                    unsupportedPlayers.remove(registration.playerId());
                        lastAction = ActionState.NOT_RUN;
                    }
                    return;
                }
                Object playerHandle = ticket.playerHandle();
                if (playerHandle == null) {
                    removeIfCurrent(registration.playerId(), ticket);
                    return;
                }
                if (!current.hasSupport(registration.playerId())) {
                    lastAction = ActionState.NOT_RUN;
                    if (registrations.remove(registration.playerId(), ticket)) {
                        states.remove(registration.playerId());
                        supportedPlayers.remove(registration.playerId());
                        unsupportedPlayers.add(registration.playerId());
                    }
                    return;
                }
                // The Apollo lookup can synchronously dispatch lifecycle callbacks;
                // revalidate identity before publishing or mutating the player.
                if (!isCurrent(registration.playerId(), ticket)) return;
                unsupportedPlayers.remove(registration.playerId());
                supportedPlayers.add(registration.playerId());
                states.put(registration.playerId(), LunarPlayerState.REGISTERED);
                if (!config.minimapEnabled()) {
                    lastAction = ActionState.NOT_RUN;
                    return;
                }
                LunarClientIntegration.MinimapAction applied =
                        current.disableMinimap(registration.playerId(), playerHandle);
                availability = safeAvailability(current);
                if (!isCurrent(registration.playerId(), ticket)) return;
                if (applied == LunarClientIntegration.MinimapAction.FAILED || applied == null) {
                    fail(registration.playerId(), registration.playerName(), ticket,
                            new IllegalStateException("Apollo did not accept the Minimap override"));
                    return;
                }
                states.put(registration.playerId(), LunarPlayerState.MINIMAP_DISABLED);
                lastAction = applied == LunarClientIntegration.MinimapAction.ALREADY_APPLIED
                        ? ActionState.ALREADY_APPLIED : ActionState.APPLIED;
            } catch (LinkageError | RuntimeException exception) {
                availability = safeAvailability(current);
                fail(registration.playerId(), registration.playerName(), ticket, exception);
            }
        }
    }

    private boolean isCurrent(UUID playerId, RegistrationTicket ticket) {
        return !stopped && registrations.get(playerId) == ticket && !ticket.cancelled.get();
    }

    private void removeIfCurrent(UUID playerId, RegistrationTicket ticket) {
        if (registrations.remove(playerId, ticket)) {
            states.remove(playerId);
            supportedPlayers.remove(playerId);
            unsupportedPlayers.remove(playerId);
        }
    }

    private void fail(UUID playerId, String playerName, RegistrationTicket ticket, Throwable exception) {
        if (!isCurrent(playerId, ticket)) return;
        states.put(playerId, LunarPlayerState.FAILED);
        lastAction = ActionState.FAILED;
        logger.log(Level.FINE, "[VAntiCheat] Lunar/Apollo action failed player=" + playerName, exception);
    }

    private static LunarClientIntegration.Availability safeAvailability(LunarClientIntegration integration) {
        try {
            LunarClientIntegration.Availability result = integration.availability();
            return result == null ? LunarClientIntegration.Availability.FAILED : result;
        } catch (LinkageError | RuntimeException exception) {
            return LunarClientIntegration.Availability.FAILED;
        }
    }

    /** Ignores delayed unregistrations from an older connection with the same UUID. */
    public void handleUnregister(LunarClientIntegration.Registration registration) {
        if (registration == null) return;
        removeConnection(registration.playerId(), registration.playerHandle());
    }

    public void handleQuit(UUID playerId, Object playerHandle) {
        if (playerId == null) return;
        removeConnection(playerId, playerHandle);
    }

    /** Quiesces callbacks and player work while the optional Apollo plugin is disabled. */
    public void apolloDisabled() {
        synchronized (lifecycleGate) {
            if (stopped || integration == null) return;
            apolloSuspended = true;
            for (RegistrationTicket ticket : registrations.values()) cancelPending(ticket);
            registrations.clear();
            states.clear();
            supportedPlayers.clear();
            unsupportedPlayers.clear();
            lastAction = ActionState.NOT_RUN;
            try {
                integration.stop();
            } catch (LinkageError | RuntimeException exception) {
                logger.log(Level.FINE, "[VAntiCheat] Apollo disable cleanup failed", exception);
            }
            availability = LunarClientIntegration.Availability.NOT_READY;
        }
    }

    /** Reattaches the same optional bridge after Apollo reports enabled; no new reflection occurs. */
    public void apolloEnabled() {
        synchronized (lifecycleGate) {
            LunarClientIntegration current = integration;
            if (stopped || !config.enabled() || current == null) return;
            try {
                current.start();
                availability = safeAvailability(current);
                apolloSuspended = false;
            } catch (LinkageError | RuntimeException exception) {
                availability = LunarClientIntegration.Availability.FAILED;
                apolloSuspended = true;
                logger.log(Level.FINE, "[VAntiCheat] Apollo re-enable failed", exception);
            }
        }
    }

    private void removeConnection(UUID playerId, Object playerHandle) {
        synchronized (lifecycleGate) {
            RegistrationTicket ticket = registrations.get(playerId);
            if (ticket != null && ticket.matches(playerHandle)
                    && registrations.remove(playerId, ticket)) {
                cancelPending(ticket);
                states.remove(playerId);
                supportedPlayers.remove(playerId);
                unsupportedPlayers.remove(playerId);
            }
        }
    }

    public boolean isLunar(UUID playerId) {
        return playerId != null && supportedPlayers.contains(playerId);
    }

    public LunarSnapshot snapshot(UUID playerId) {
        LunarPlayerState state = playerId == null
                ? LunarPlayerState.UNKNOWN
                : states.getOrDefault(playerId, LunarPlayerState.UNKNOWN);
        Boolean support = playerId == null ? null : supportedPlayers.contains(playerId) ? Boolean.TRUE
                : unsupportedPlayers.contains(playerId) ? Boolean.FALSE : null;
        return new LunarSnapshot(integrationStatus(), support,
                config.minimapEnabled(), state);
    }

    public int trackedPlayers() { return states.size(); }

    public boolean enabled() { return config.enabled(); }

    public LunarClientIntegration.Availability integrationStatus() {
        if (!config.enabled()) return LunarClientIntegration.Availability.DISABLED;
        if (integration == null) return LunarClientIntegration.Availability.NOT_PRESENT;
        if (stopped || apolloSuspended) return LunarClientIntegration.Availability.NOT_READY;
        return availability;
    }

    public ActionState lastAction() { return lastAction; }

    private void cancelPending(RegistrationTicket ticket) {
        try {
            ticket.cancelPending();
        } catch (LinkageError | RuntimeException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Lunar task cancellation failed", exception);
        }
    }

    private static final class RegistrationTicket {
        private final WeakReference<Object> playerHandle;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicReference<TaskHandle> task = new AtomicReference<>();

        private RegistrationTicket(LunarClientIntegration.Registration registration) {
            this.playerHandle = new WeakReference<>(registration.playerHandle());
        }

        private Object playerHandle() { return playerHandle.get(); }

        private boolean matches(Object expected) {
            return expected != null && playerHandle.get() == expected;
        }

        private void setTask(TaskHandle handle) {
            if (handle == null) return;
            if (started.get() || cancelled.get()) handle.cancel();
            else {
                task.set(handle);
                if (started.get() || cancelled.get()) cancelPending();
            }
        }

        private void markStarted() {
            started.set(true);
            task.set(null);
        }

        private void cancelPending() {
            cancelled.set(true);
            TaskHandle pending = task.getAndSet(null);
            if (pending != null) pending.cancel();
        }
    }
}
