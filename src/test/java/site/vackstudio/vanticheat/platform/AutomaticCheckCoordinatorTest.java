package site.vackstudio.vanticheat.platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticCheckCoordinatorTest {
    private final FakeScheduler scheduler = new FakeScheduler();
    private final UUID playerId = UUID.randomUUID();

    @Test
    void delayAndDuplicateAdmissionArePerPlayer() {
        AutomaticCheckCoordinator coordinator = new AutomaticCheckCoordinator(
                scheduler, 1, false, 2, Logger.getLogger("test-join"));
        AtomicInteger checks = new AtomicInteger();
        AutomaticCheckCoordinator.Target target = target(playerId, true, true);

        assertTrue(coordinator.schedule(target, checks::incrementAndGet));
        assertFalse(coordinator.schedule(target, checks::incrementAndGet));
        assertEquals(1, coordinator.activeCount());
        assertEquals(0, checks.get());

        scheduler.runDelayed();
        assertEquals(1, checks.get());
        coordinator.complete(playerId);
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void firstJoinOnlyAndConcurrencyLimitAreEnforced() {
        AutomaticCheckCoordinator coordinator = new AutomaticCheckCoordinator(
                scheduler, 1, true, 1, Logger.getLogger("test-join"));
        AtomicInteger checks = new AtomicInteger();

        assertFalse(coordinator.schedule(target(UUID.randomUUID(), true, false), checks::incrementAndGet));
        assertTrue(coordinator.schedule(target(playerId, true, true), checks::incrementAndGet));
        assertFalse(coordinator.schedule(target(UUID.randomUUID(), true, true), checks::incrementAndGet));
        coordinator.disconnect(playerId);
        assertEquals(0, coordinator.activeCount());
        scheduler.runDelayed();
        assertEquals(0, checks.get());
    }

    @Test
    void disconnectBeforeDelayCancelsAutomaticCheck() {
        AutomaticCheckCoordinator coordinator = new AutomaticCheckCoordinator(
                scheduler, 1, false, 2, Logger.getLogger("test-join"));
        AtomicInteger checks = new AtomicInteger();

        coordinator.schedule(target(playerId, true, true), checks::incrementAndGet);
        coordinator.disconnect(playerId);
        scheduler.runDelayed();

        assertEquals(0, checks.get());
        assertFalse(coordinator.active(playerId));
    }

    private static AutomaticCheckCoordinator.Target target(UUID id, boolean online, boolean firstJoin) {
        return new AutomaticCheckCoordinator.Target(id, "player", online, firstJoin, new Object());
    }

    private static final class FakeScheduler implements Scheduler {
        private final Queue<Runnable> delayed = new ArrayDeque<>();

        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) {
            task.run();
            return handle();
        }

        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) {
            task.run();
            return handle();
        }

        @Override public TaskHandle runGlobal(Runnable task) {
            task.run();
            return handle();
        }

        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
            delayed.add(task);
            return handle();
        }

        @Override public TaskHandle runAsync(Runnable task) {
            task.run();
            return handle();
        }

        void runDelayed() {
            Runnable task;
            while ((task = delayed.poll()) != null) task.run();
        }

        @Override public void shutdown() { }

        private static TaskHandle handle() {
            return new TaskHandle() {
                private boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }
    }
}
