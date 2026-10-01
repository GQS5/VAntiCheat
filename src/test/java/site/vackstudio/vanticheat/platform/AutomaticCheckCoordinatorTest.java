package site.vackstudio.vanticheat.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticCheckCoordinatorTest {
    private final FakeScheduler scheduler = new FakeScheduler();
    private final UUID playerId = UUID.randomUUID();

    @Test
    void delayAndDuplicateAdmissionArePerPlayer() {
        AutomaticCheckCoordinator coordinator = coordinator(2);
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
    void diagnosticsCountersTrackAdmissionStartCompletionAndSkip() {
        AutomaticCheckCoordinator coordinator = coordinator(1);
        AutomaticCheckCoordinator.Target target = target(playerId, true, true);
        AutomaticCheckCoordinator.Ticket ticket = coordinator.scheduleTicket(target, ignored -> { });
        assertNotNull(ticket);
        assertNull(coordinator.scheduleTicket(target, ignored -> { }));

        var admitted = coordinator.metrics();
        assertEquals(1, admitted.active());
        assertEquals(1, admitted.capacity());
        assertEquals(1, admitted.admitted());
        assertEquals(1, admitted.skippedAdmissions());
        assertEquals(0, admitted.started());

        scheduler.runDelayed();
        assertEquals(1, coordinator.metrics().started());
        assertTrue(coordinator.complete(ticket));
        assertEquals(1, coordinator.metrics().released());
        assertEquals(0, coordinator.metrics().active());
    }

    @Test
    void capacityIsAtomicAndReleasedOnCompletionOrDisconnect() {
        AutomaticCheckCoordinator coordinator = coordinator(1);
        AtomicInteger checks = new AtomicInteger();
        UUID secondId = UUID.randomUUID();

        assertTrue(coordinator.schedule(target(playerId, true, true), checks::incrementAndGet));
        assertFalse(coordinator.schedule(target(secondId, true, true), checks::incrementAndGet));
        assertEquals(1, coordinator.activeCount());
        coordinator.disconnect(playerId);
        assertEquals(0, coordinator.activeCount());
        assertTrue(coordinator.schedule(target(secondId, true, true), checks::incrementAndGet));
        scheduler.runDelayed();
        assertEquals(1, checks.get());
        coordinator.complete(secondId);
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void firstJoinOnlyIsAppliedAtAdmission() {
        AutomaticCheckCoordinator coordinator = new AutomaticCheckCoordinator(
                scheduler, 1, true, 2, Logger.getLogger("test-first-join"));
        AtomicInteger checks = new AtomicInteger();

        assertFalse(coordinator.schedule(target(playerId, true, false), checks::incrementAndGet));
        assertTrue(coordinator.schedule(target(playerId, true, true), checks::incrementAndGet));
        scheduler.runDelayed();
        assertEquals(1, checks.get());
    }

    @Test
    void staleTicketCannotCompleteReconnectOperation() {
        AutomaticCheckCoordinator coordinator = coordinator(2);
        AtomicInteger checks = new AtomicInteger();
        AutomaticCheckCoordinator.Ticket oldTicket = coordinator.scheduleTicket(
                target(playerId, true, true), ignored -> checks.incrementAndGet());
        assertNotNull(oldTicket);
        coordinator.disconnect(playerId);
        AutomaticCheckCoordinator.Ticket reconnectTicket = coordinator.scheduleTicket(
                target(playerId, true, true), ignored -> checks.incrementAndGet());
        assertNotNull(reconnectTicket);

        assertFalse(coordinator.complete(oldTicket));
        assertTrue(coordinator.isCurrent(reconnectTicket));
        scheduler.runDelayed(); // includes the cancelled late task for the old ticket
        assertEquals(1, checks.get());
        assertTrue(coordinator.isCurrent(reconnectTicket));
        assertTrue(coordinator.complete(reconnectTicket));
        assertFalse(coordinator.complete(reconnectTicket));
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void resultClaimIsExactlyOnceAndHoldsCapacityUntilFinalCleanup() {
        AutomaticCheckCoordinator coordinator = coordinator(1);
        AutomaticCheckCoordinator.Ticket ticket = coordinator.scheduleTicket(
                target(playerId, true, true), ignored -> { });
        scheduler.runDelayed();

        assertNotNull(ticket);
        assertTrue(coordinator.claimCompletion(ticket));
        assertFalse(coordinator.claimCompletion(ticket));
        assertEquals(1, coordinator.activeCount(), "capacity remains held during result/enforcement handling");
        assertTrue(coordinator.complete(ticket));
        assertFalse(coordinator.complete(ticket));
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void duplicateScheduledCallbacksStartOnlyOneCheck() {
        AutomaticCheckCoordinator coordinator = coordinator(2);
        AtomicInteger checks = new AtomicInteger();
        coordinator.schedule(target(playerId, true, true), checks::incrementAndGet);

        scheduler.runFirstDelayedTwice();

        assertEquals(1, checks.get());
        assertEquals(1, coordinator.activeCount());
        coordinator.complete(playerId);
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void dynamicOfflineStatePreventsEntityStartAndReleasesCapacity() {
        AutomaticCheckCoordinator coordinator = coordinator(1);
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicInteger checks = new AtomicInteger();
        AutomaticCheckCoordinator.Target target = new AutomaticCheckCoordinator.Target(
                playerId, "player", online::get, true, new Object());

        assertNotNull(coordinator.scheduleTicket(target, ignored -> checks.incrementAndGet()));
        online.set(false);
        scheduler.runDelayed();

        assertEquals(0, checks.get());
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void rejectedFirstJoinAndConcurrencyDoNotLeakState() {
        AutomaticCheckCoordinator coordinator = new AutomaticCheckCoordinator(
                scheduler, 1, true, 1, Logger.getLogger("test-join"));
        AtomicInteger checks = new AtomicInteger();

        assertFalse(coordinator.schedule(target(UUID.randomUUID(), true, false), checks::incrementAndGet));
        assertTrue(coordinator.schedule(target(playerId, true, true), checks::incrementAndGet));
        assertFalse(coordinator.schedule(target(UUID.randomUUID(), true, true), checks::incrementAndGet));
        coordinator.disconnect(playerId);
        scheduler.runDelayed();
        assertEquals(0, checks.get());
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void stopReleasesPendingAndRejectsNewAdmission() {
        AutomaticCheckCoordinator coordinator = coordinator(2);
        coordinator.schedule(target(playerId, true, true), () -> { });

        coordinator.stop();

        assertEquals(0, coordinator.activeCount());
        assertFalse(coordinator.schedule(target(UUID.randomUUID(), true, true), () -> { }));
        scheduler.runDelayed();
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void entitySchedulingFailureReleasesTicketAndReportsFailureOnce() {
        scheduler.failEntityScheduling = true;
        AutomaticCheckCoordinator coordinator = coordinator(1);
        AtomicInteger failures = new AtomicInteger();

        AutomaticCheckCoordinator.Ticket ticket = coordinator.scheduleTicket(
                target(playerId, true, true), ignored -> { }, ignored -> { }, ignored -> failures.incrementAndGet());
        assertNotNull(ticket);
        scheduler.runDelayed();

        assertEquals(0, coordinator.activeCount());
        assertEquals(1, failures.get());
        assertFalse(coordinator.complete(ticket));
    }

    @Test
    void retiredEntityContextReleasesTicketAndDoesNotRunPlayerWork() {
        scheduler.retireEntityScheduling = true;
        AutomaticCheckCoordinator coordinator = coordinator(1);
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        var ticket = coordinator.scheduleTicket(target(playerId, true, true), ignored -> { },
                ignored -> checks.incrementAndGet(), ignored -> failures.incrementAndGet());

        scheduler.runDelayed();

        assertEquals(0, checks.get());
        assertEquals(1, failures.get());
        assertEquals(0, coordinator.activeCount());
        assertEquals(1, coordinator.metrics().released());
        assertFalse(coordinator.complete(ticket));
    }

    @Test
    void accountingIsBoundedAcrossConfiguredPlayerCounts() {
        for (int capacity : List.of(1, 4, 8, 16, 32, 40)) {
            AutomaticCheckCoordinator coordinator = coordinator(capacity);
            for (int i = 0; i < capacity; i++) {
                assertTrue(coordinator.schedule(target(UUID.randomUUID(), true, true), () -> { }));
            }
            assertEquals(capacity, coordinator.activeCount());
            assertFalse(coordinator.schedule(target(UUID.randomUUID(), true, true), () -> { }));
            coordinator.stop();
            assertEquals(0, coordinator.activeCount());
        }
    }

    @Test
    void concurrentAdmissionsNeverExceedConfiguredCapacity() throws Exception {
        int capacity = 16;
        AutomaticCheckCoordinator coordinator = coordinator(capacity);
        AtomicInteger admitted = new AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(12)) {
            List<java.util.concurrent.Future<?>> submissions = new java.util.ArrayList<>();
            for (int i = 0; i < 80; i++) {
                UUID id = UUID.randomUUID();
                submissions.add(executor.submit(() -> {
                    if (coordinator.schedule(target(id, true, true), () -> { })) admitted.incrementAndGet();
                }));
            }
            for (var submission : submissions) submission.get();
        }
        assertEquals(capacity, admitted.get());
        assertEquals(capacity, coordinator.activeCount());
        coordinator.stop();
        assertEquals(0, coordinator.activeCount());
    }

    private AutomaticCheckCoordinator coordinator(int maxConcurrent) {
        return new AutomaticCheckCoordinator(scheduler, 1, false, maxConcurrent,
                Logger.getLogger("test-join"));
    }

    private static AutomaticCheckCoordinator.Target target(UUID id, boolean online, boolean firstJoin) {
        return new AutomaticCheckCoordinator.Target(id, "player", online, firstJoin, new Object());
    }

    private static final class FakeScheduler implements Scheduler {
        private final Queue<Delayed> delayed = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private volatile boolean failEntityScheduling;
        private volatile boolean retireEntityScheduling;

        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) {
            if (failEntityScheduling) throw new IllegalStateException("entity scheduler unavailable");
            task.run();
            return handle();
        }

        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task, Runnable retired) {
            if (retireEntityScheduling) {
                retired.run();
                return handle();
            }
            return runAtEntity(target, task);
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
            FakeHandle handle = new FakeHandle();
            delayed.add(new Delayed(task, handle));
            return handle;
        }

        @Override public TaskHandle runAsync(Runnable task) {
            task.run();
            return handle();
        }

        void runDelayed() {
            Delayed entry;
            while ((entry = delayed.poll()) != null) entry.task().run();
        }

        void runFirstDelayedTwice() {
            Delayed entry = delayed.poll();
            entry.task().run();
            entry.task().run();
        }

        @Override public void shutdown() { }

        private static FakeHandle handle() { return new FakeHandle(); }

        private record Delayed(Runnable task, FakeHandle handle) { }
    }

    private static final class FakeHandle implements TaskHandle {
        private volatile boolean cancelled;
        @Override public void cancel() { cancelled = true; }
        @Override public boolean cancelled() { return cancelled; }
    }
}
