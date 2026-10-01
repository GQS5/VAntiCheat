package site.vackstudio.vanticheat.platform;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/** Owns delayed automatic-check admission and per-player duplicate protection. */
public final class AutomaticCheckCoordinator {
    public record MetricsSnapshot(int active, int capacity, long admitted, long started,
                                  long released, long skippedAdmissions, long failures) { }

    private final Scheduler scheduler;
    private volatile long delayTicks;
    private volatile boolean firstJoinOnly;
    private volatile int maxConcurrent;
    private final Logger logger;
    private final ConcurrentMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final LongAdder admittedCount = new LongAdder();
    private final LongAdder startedCount = new LongAdder();
    private final LongAdder releasedCount = new LongAdder();
    private final LongAdder skippedAdmissions = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private volatile boolean stopped;

    public AutomaticCheckCoordinator(Scheduler scheduler, long delayTicks, boolean firstJoinOnly,
                                     int maxConcurrent, Logger logger) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (delayTicks < 0) throw new IllegalArgumentException("delayTicks cannot be negative");
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be positive");
        this.delayTicks = delayTicks;
        this.firstJoinOnly = firstJoinOnly;
        this.maxConcurrent = maxConcurrent;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean schedule(Target target, Runnable check) {
        Objects.requireNonNull(check, "check");
        return scheduleTicket(target, ignored -> check.run()) != null;
    }

    /** Returns an operation token so stale callbacks cannot complete a reconnect's scan. */
    public Ticket scheduleTicket(Target target, Consumer<Ticket> check) {
        return scheduleTicket(target, ignored -> { }, check);
    }

    /** Calls onAdmitted as soon as the UUID slot is reserved, before scheduling any work. */
    public Ticket scheduleTicket(Target target, Consumer<Ticket> onAdmitted, Consumer<Ticket> check) {
        return scheduleTicket(target, onAdmitted, check, ignored -> { });
    }

    public Ticket scheduleTicket(Target target, Consumer<Ticket> onAdmitted,
                                 Consumer<Ticket> check, Consumer<Ticket> onStartFailure) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(onAdmitted, "onAdmitted");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(onStartFailure, "onStartFailure");
        Pending operation;
        Ticket ticket;
        long scheduledDelay;
        synchronized (admissionLock) {
            boolean online;
            try {
                online = target.online();
            } catch (RuntimeException exception) {
                failures.increment();
                logger.warning("Automatic client check admission failed for " + target.name()
                        + ": player state unavailable");
                return null;
            }
            if (stopped || !online || (firstJoinOnly && !target.firstJoin())) {
                skippedAdmissions.increment();
                return null;
            }
            if (pending.containsKey(target.id())) {
                skippedAdmissions.increment();
                return null;
            }
            if (pending.size() >= maxConcurrent) {
                skippedAdmissions.increment();
                logger.fine("Automatic client check skipped: concurrency limit reached for " + target.name());
                return null;
            }
            operation = new Pending(target.id());
            pending.put(target.id(), operation);
            ticket = new Ticket(this, operation);
            try {
                onAdmitted.accept(ticket);
            } catch (RuntimeException exception) {
                pending.remove(target.id(), operation);
                operation.completed = true;
                failures.increment();
                logger.warning("Automatic client check admission callback failed for " + target.name());
                return null;
            }
            if (operation.completed) return null;
            admittedCount.increment();
            scheduledDelay = delayTicks;
        }
        try {
            TaskHandle delayed = scheduler.runGlobalLater(() -> {
                if (!isCurrent(ticket)) return;
                try {
                    TaskHandle entity = scheduler.runAtEntity(new EntityTarget(target.nativeEntity()), () -> {
                        if (!begin(ticket)) return;
                        try {
                            if (!target.online()) {
                                fail(ticket, onStartFailure);
                                return;
                            }
                            check.accept(ticket);
                        } catch (RuntimeException exception) {
                            logger.warning("Automatic client check start failed for " + target.name()
                                    + ": " + exception.getMessage());
                            fail(ticket, onStartFailure);
                        }
                    }, () -> failBeforeStart(ticket, onStartFailure));
                    operation.entityHandle = entity;
                    if (operation.completed) cancel(entity);
                } catch (RuntimeException exception) {
                    logger.warning("Automatic client entity scheduling failed for " + target.name()
                            + ": " + exception.getMessage());
                    fail(ticket, onStartFailure);
                }
            }, scheduledDelay);
            operation.delayHandle = delayed;
            if (operation.completed) cancel(delayed);
            return ticket;
        } catch (RuntimeException exception) {
            complete(ticket);
            failures.increment();
            logger.warning("Automatic client check scheduling failed for " + target.name()
                    + ": " + exception.getMessage());
            return null;
        }
    }

    public boolean active(UUID playerId) {
        return pending.containsKey(playerId);
    }

    public int activeCount() {
        return pending.size();
    }

    public MetricsSnapshot metrics() {
        return new MetricsSnapshot(pending.size(), maxConcurrent, admittedCount.sum(),
                startedCount.sum(), releasedCount.sum(), skippedAdmissions.sum(), failures.sum());
    }

    /** Records join-policy skips that occur before coordinator slot admission. */
    public void recordSkippedAdmission() { skippedAdmissions.increment(); }

    public void reconfigure(long delayTicks, boolean firstJoinOnly, int maxConcurrent) {
        if (delayTicks < 0) throw new IllegalArgumentException("delayTicks cannot be negative");
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be positive");
        synchronized (admissionLock) {
            this.delayTicks = delayTicks;
            this.firstJoinOnly = firstJoinOnly;
            this.maxConcurrent = maxConcurrent;
        }
    }

    public void complete(UUID playerId) {
        Pending operation = pending.get(playerId);
        if (operation != null) complete(new Ticket(this, operation));
    }

    public boolean complete(Ticket ticket) {
        if (ticket == null || ticket.owner != this) return false;
        boolean removed;
        synchronized (admissionLock) {
            removed = pending.remove(ticket.operation.playerId, ticket.operation);
            if (removed) {
                ticket.operation.completed = true;
                releasedCount.increment();
            }
        }
        if (removed) cancelHandles(ticket.operation);
        return removed;
    }

    /** Claims the one final-result callback without releasing concurrency capacity yet. */
    public boolean claimCompletion(Ticket ticket) {
        if (ticket == null || ticket.owner != this) return false;
        synchronized (admissionLock) {
            return !ticket.operation.completed
                    && pending.get(ticket.operation.playerId) == ticket.operation
                    && ticket.operation.completionClaimed.compareAndSet(false, true);
        }
    }

    public boolean isCurrent(Ticket ticket) {
        return ticket != null && ticket.owner == this
                && !ticket.operation.completed
                && pending.get(ticket.operation.playerId) == ticket.operation;
    }

    public boolean isStarted(Ticket ticket) {
        return isCurrent(ticket) && ticket.operation.started;
    }

    private boolean begin(Ticket ticket) {
        synchronized (admissionLock) {
            if (ticket.operation.completed || ticket.operation.started
                    || pending.get(ticket.operation.playerId) != ticket.operation) return false;
            ticket.operation.started = true;
            startedCount.increment();
            return true;
        }
    }

    private void fail(Ticket ticket, Consumer<Ticket> onStartFailure) {
        if (complete(ticket)) {
            failures.increment();
            try {
                onStartFailure.accept(ticket);
            } catch (RuntimeException exception) {
                logger.warning("Automatic client check failure callback failed for "
                        + ticket.playerId() + ": " + exception.getClass().getSimpleName());
            }
        }
    }

    private void failBeforeStart(Ticket ticket, Consumer<Ticket> onStartFailure) {
        if (isCurrent(ticket) && !isStarted(ticket)) fail(ticket, onStartFailure);
    }

    public void disconnect(UUID playerId) {
        complete(playerId);
    }

    /** Cancels admission-delay work while leaving already-running sessions alone. */
    public void cancelNotStarted() {
        List<Pending> cancelled = new java.util.ArrayList<>();
        synchronized (admissionLock) {
            for (Pending operation : pending.values()) {
                if (!operation.started && pending.remove(operation.playerId, operation)) {
                    operation.completed = true;
                    releasedCount.increment();
                    skippedAdmissions.increment();
                    cancelled.add(operation);
                }
            }
        }
        cancelled.forEach(AutomaticCheckCoordinator::cancelHandles);
    }

    public void stop() {
        stopped = true;
        for (UUID playerId : List.copyOf(pending.keySet())) disconnect(playerId);
    }

    private static void cancelHandles(Pending operation) {
        TaskHandle delayed = operation.delayHandle;
        cancel(delayed);
        TaskHandle entity = operation.entityHandle;
        cancel(entity);
    }

    private static void cancel(TaskHandle handle) {
        if (handle == null) return;
        try {
            if (!handle.cancelled()) handle.cancel();
        } catch (RuntimeException ignored) {
            // Cancellation is best-effort; the operation token prevents stale work from running.
        }
    }

    public record Target(UUID id, String name, BooleanSupplier onlineState,
                         boolean firstJoin, Object nativeEntity) {
        public Target(UUID id, String name, boolean online, boolean firstJoin, Object nativeEntity) {
            this(id, name, () -> online, firstJoin, nativeEntity);
        }

        public Target {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(onlineState, "onlineState");
            Objects.requireNonNull(nativeEntity, "nativeEntity");
        }

        public boolean online() { return onlineState.getAsBoolean(); }
    }

    public static final class Ticket {
        private final AutomaticCheckCoordinator owner;
        private final Pending operation;
        private Ticket(AutomaticCheckCoordinator owner, Pending operation) {
            this.owner = owner;
            this.operation = operation;
        }
        public UUID playerId() { return operation.playerId; }
    }

    private static final class Pending {
        private final UUID playerId;
        private volatile TaskHandle delayHandle;
        private volatile TaskHandle entityHandle;
        private volatile boolean completed;
        private volatile boolean started;
        private final java.util.concurrent.atomic.AtomicBoolean completionClaimed =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Pending(UUID playerId) { this.playerId = playerId; }
    }
}
