package site.vackstudio.vanticheat.platform;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/** Owns delayed automatic-check admission and per-player duplicate protection. */
public final class AutomaticCheckCoordinator {
    private final Scheduler scheduler;
    private final long delayTicks;
    private final boolean firstJoinOnly;
    private final int maxConcurrent;
    private final Logger logger;
    private final ConcurrentMap<UUID, Pending> pending = new ConcurrentHashMap<>();

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
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(check, "check");
        if (!target.online() || (firstJoinOnly && !target.firstJoin())) return false;
        if (pending.size() >= maxConcurrent) {
            logger.warning("Automatic client check skipped: concurrency limit reached for " + target.name());
            return false;
        }
        Pending operation = new Pending(target.id());
        if (pending.putIfAbsent(target.id(), operation) != null) return false;
        TaskHandle delayed = scheduler.runGlobalLater(() -> {
            if (pending.get(target.id()) != operation || !target.online()) return;
            scheduler.runAtEntity(new EntityTarget(target.nativeEntity()), () -> {
                if (pending.get(target.id()) == operation && target.online()) check.run();
            });
        }, delayTicks);
        operation.handle = delayed;
        if (operation.completed) delayed.cancel();
        return true;
    }

    public boolean active(UUID playerId) {
        return pending.containsKey(playerId);
    }

    public int activeCount() {
        return pending.size();
    }

    public void complete(UUID playerId) {
        Pending operation = pending.remove(playerId);
        if (operation != null) operation.completed = true;
    }

    public void disconnect(UUID playerId) {
        Pending operation = pending.remove(playerId);
        if (operation != null) {
            operation.completed = true;
            if (operation.handle != null) operation.handle.cancel();
        }
    }

    public void stop() {
        for (UUID playerId : List.copyOf(pending.keySet())) disconnect(playerId);
    }

    public record Target(UUID id, String name, boolean online, boolean firstJoin, Object nativeEntity) {
        public Target {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nativeEntity, "nativeEntity");
        }
    }

    private static final class Pending {
        private final UUID playerId;
        private volatile TaskHandle handle;
        private volatile boolean completed;

        private Pending(UUID playerId) { this.playerId = playerId; }
    }
}
