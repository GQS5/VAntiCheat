package site.vackstudio.vanticheat.lunar;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lunar is an optional Apollo side effect and never a client-detection verdict. */
class LunarClientServiceTest {
    private final UUID playerId = UUID.randomUUID();

    @Test
    void absentApolloLeavesServiceAndDetectionIndependent() {
        TestScheduler scheduler = new TestScheduler();
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), null);

        assertEquals(LunarClientIntegration.Availability.NOT_PRESENT, service.integrationStatus());
        assertEquals(LunarClientService.RegistrationOutcome.IGNORED_UNAVAILABLE,
                service.handleRegistration(registration(new Object())));
        assertEquals(0, scheduler.entityTasks.size());
        assertEquals(0, service.trackedPlayers());
    }

    @Test
    void readyApolloActionRunsOnPlayerEntityContext() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object player = new Object();
        integration.currentPlayer = player;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);

        assertEquals(LunarClientIntegration.Availability.AVAILABLE, service.integrationStatus());
        assertEquals(LunarClientService.RegistrationOutcome.SCHEDULED,
                service.handleRegistration(registration(player)));
        assertEquals(0, integration.settingWrites);
        scheduler.runEntityTasks();

        assertEquals(1, integration.settingWrites);
        assertTrue(service.isLunar(playerId));
        assertEquals(LunarPlayerState.MINIMAP_DISABLED, service.snapshot(playerId).state());
        assertEquals(LunarClientService.ActionState.APPLIED, service.lastAction());
    }

    @Test
    void presentButUnreadyIsReportedWithoutRepeatingSchedulerWork() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        integration.readiness = LunarClientIntegration.Availability.NOT_READY;
        integration.currentPlayer = new Object();
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);

        assertEquals(LunarClientIntegration.Availability.NOT_READY, service.integrationStatus());
        service.handleRegistration(registration(integration.currentPlayer));
        scheduler.runEntityTasks();

        assertEquals(1, scheduler.entitySchedules);
        assertEquals(0, integration.supportCalls);
        assertEquals(0, integration.settingWrites);
        assertEquals(LunarClientIntegration.Availability.NOT_READY, service.integrationStatus());
        assertEquals(LunarClientService.ActionState.NOT_RUN, service.lastAction());
    }

    @Test
    void repeatedRegistrationsKeepTheOverrideIdempotent() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object player = new Object();
        integration.currentPlayer = player;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);

        for (int i = 0; i < 3; i++) {
            service.handleRegistration(registration(player));
            scheduler.runEntityTasks();
        }

        assertEquals(1, integration.settingWrites);
        assertEquals(2, integration.alreadyAppliedCount);
        assertEquals(LunarClientService.ActionState.ALREADY_APPLIED, service.lastAction());
        assertEquals(LunarPlayerState.MINIMAP_DISABLED, service.snapshot(playerId).state());
    }

    @Test
    void apiFailureIsRecordedSeparatelyWithoutErasingLunarSupport() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object player = new Object();
        integration.currentPlayer = player;
        integration.throwOnMutation = true;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);

        service.handleRegistration(registration(player));
        scheduler.runEntityTasks();

        assertTrue(service.isLunar(playerId));
        assertEquals(LunarPlayerState.FAILED, service.snapshot(playerId).state());
        assertEquals(LunarClientService.ActionState.FAILED, service.lastAction());
        assertEquals(LunarClientIntegration.Availability.FAILED, service.integrationStatus());
    }

    @Test
    void quitAndReconnectUseOnlyTheCurrentPlayerIdentity() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object playerA = new Object();
        Object playerB = new Object();
        integration.currentPlayer = playerA;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);

        LunarClientIntegration.Registration old = registration(playerA);
        service.handleRegistration(old);
        service.handleQuit(playerId, playerA);
        assertTrue(scheduler.entityTasks.peek().handle.cancelled());

        integration.currentPlayer = playerB;
        service.handleRegistration(registration(playerB));
        scheduler.runEntityTasks();
        service.handleUnregister(old); // delayed unregister from the old connection

        assertEquals(1, integration.settingWrites);
        assertTrue(service.isLunar(playerId));
        assertEquals(LunarPlayerState.MINIMAP_DISABLED, service.snapshot(playerId).state());
    }

    @Test
    void quitReenteredDuringApolloLookupPreventsStalePlayerMutation() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object playerA = new Object();
        Object playerB = new Object();
        integration.currentPlayer = playerA;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);
        integration.afterSupportLookup = () -> {
            service.handleQuit(playerId, playerA);
            integration.currentPlayer = playerB;
            service.handleRegistration(registration(playerB));
        };

        service.handleRegistration(registration(playerA));
        scheduler.runEntityTasks();

        assertEquals(1, integration.settingWrites, "only the replacement connection receives the action");
        assertTrue(service.isLunar(playerId));
        assertEquals(LunarPlayerState.MINIMAP_DISABLED, service.snapshot(playerId).state());
    }

    @Test
    void shutdownCancelsPendingWorkAndRejectsLaterRegistrations() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object player = new Object();
        integration.currentPlayer = player;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);
        service.handleRegistration(registration(player));

        service.stop();
        scheduler.runEntityTasks();

        assertEquals(0, integration.settingWrites);
        assertTrue(integration.stopped);
        assertEquals(LunarClientService.RegistrationOutcome.IGNORED_DISABLED,
                service.handleRegistration(registration(player)));
        assertEquals(LunarClientIntegration.Availability.NOT_PRESENT, service.integrationStatus());
    }

    @Test
    void ApolloDisableCancelsPendingWorkAndReenableReusesTheBridge() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object player = new Object();
        integration.currentPlayer = player;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);
        service.handleRegistration(registration(player));

        service.apolloDisabled();
        assertEquals(LunarClientIntegration.Availability.NOT_READY, service.integrationStatus());
        scheduler.runEntityTasks();
        assertEquals(0, integration.settingWrites);
        assertTrue(integration.stopped);

        service.apolloEnabled();
        service.handleRegistration(registration(player));
        scheduler.runEntityTasks();
        assertEquals(2, integration.startCalls);
        assertEquals(1, integration.settingWrites);
        assertEquals(LunarClientIntegration.Availability.AVAILABLE, service.integrationStatus());
    }

    @Test
    void settingWorkCannotRunOutsideEntityScheduler() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object player = new Object();
        integration.currentPlayer = player;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);
        service.handleRegistration(registration(player));

        assertThrows(AssertionError.class, () -> integration.hasSupport(playerId));
        scheduler.runEntityTasks();
        assertEquals(1, integration.settingWrites);
        assertEquals(1, scheduler.entitySchedules);
    }

    @Test
    void ApolloEntitySchedulingFailureIsIsolatedAndRecorded() {
        TestScheduler scheduler = new TestScheduler();
        scheduler.failEntityScheduling = true;
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object player = new Object();
        integration.currentPlayer = player;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);

        assertEquals(LunarClientService.RegistrationOutcome.FAILED,
                service.handleRegistration(registration(player)));
        assertEquals(LunarPlayerState.FAILED, service.snapshot(playerId).state());
        assertEquals(LunarClientIntegration.Availability.AVAILABLE, service.integrationStatus());
        assertEquals(0, integration.settingWrites);
        assertEquals(0, scheduler.entityTasks.size());
    }

    @Test
    void ApolloEntityRetirementDoesNotRunApiMutationOffContext() {
        TestScheduler scheduler = new TestScheduler();
        scheduler.retireEntityScheduling = true;
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        Object player = new Object();
        integration.currentPlayer = player;
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);

        assertEquals(LunarClientService.RegistrationOutcome.SCHEDULED,
                service.handleRegistration(registration(player)));

        assertEquals(0, integration.settingWrites);
        assertEquals(LunarPlayerState.FAILED, service.snapshot(playerId).state());
    }

    @Test
    void disabledPolicyDoesNotLoadOrStartApollo() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        LunarClientService service = service(scheduler, LunarPolicyConfig.disabled(), integration);

        assertEquals(LunarClientIntegration.Availability.DISABLED, service.integrationStatus());
        assertEquals(0, integration.startCalls);
        assertEquals(LunarClientService.RegistrationOutcome.IGNORED_DISABLED,
                service.handleRegistration(registration(new Object())));
    }

    @Test
    void statusReadsUseCachedApolloAvailability() {
        TestScheduler scheduler = new TestScheduler();
        FakeIntegration integration = new FakeIntegration(scheduler, playerId);
        LunarClientService service = service(scheduler, LunarPolicyConfig.defaults(), integration);
        int checksAfterStart = integration.availabilityCalls;

        for (int i = 0; i < 5; i++) {
            assertEquals(LunarClientIntegration.Availability.AVAILABLE, service.integrationStatus());
            service.snapshot(playerId);
        }
        assertEquals(checksAfterStart, integration.availabilityCalls);
    }

    private LunarClientIntegration.Registration registration(Object player) {
        return new LunarClientIntegration.Registration(playerId, "lunar", player);
    }

    private static LunarClientService service(TestScheduler scheduler, LunarPolicyConfig config,
                                               LunarClientIntegration integration) {
        LunarClientService service = new LunarClientService(Logger.getAnonymousLogger(), scheduler);
        service.start(config, integration);
        return service;
    }

    private static final class FakeIntegration implements LunarClientIntegration {
        private final TestScheduler scheduler;
        private final Set<UUID> lunar = new HashSet<>();
        private Object currentPlayer;
        private Availability readiness = Availability.AVAILABLE;
        private boolean overrideSet;
        private boolean throwOnMutation;
        private boolean stopped;
        private int startCalls;
        private int availabilityCalls;
        private int supportCalls;
        private int settingWrites;
        private int alreadyAppliedCount;
        private Runnable afterSupportLookup;

        private FakeIntegration(TestScheduler scheduler, UUID playerId) {
            this.scheduler = scheduler;
            lunar.add(playerId);
        }

        @Override public Availability availability() { availabilityCalls++; return readiness; }

        @Override public boolean hasSupport(UUID id) {
            assertTrue(scheduler.inEntityContext);
            supportCalls++;
            Runnable hook = afterSupportLookup;
            afterSupportLookup = null;
            if (hook != null) hook.run();
            return lunar.contains(id);
        }

        @Override public MinimapAction disableMinimap(UUID id, Object playerHandle) {
            assertTrue(scheduler.inEntityContext);
            assertTrue(lunar.contains(id) && currentPlayer == playerHandle);
            if (throwOnMutation) {
                readiness = Availability.FAILED;
                throw new IllegalStateException("stub API failure");
            }
            if (overrideSet) {
                alreadyAppliedCount++;
                return MinimapAction.ALREADY_APPLIED;
            }
            overrideSet = true;
            settingWrites++;
            return MinimapAction.APPLIED;
        }

        @Override public Optional<Boolean> minimapStatus(UUID id) {
            return lunar.contains(id) ? Optional.of(!overrideSet) : Optional.empty();
        }

        @Override public void start() { startCalls++; stopped = false; }
        @Override public void stop() { stopped = true; }
    }

    private static final class TestScheduler implements Scheduler {
        private final Queue<EntityTask> entityTasks = new ArrayDeque<>();
        private int entitySchedules;
        private boolean inEntityContext;
        private boolean failEntityScheduling;
        private boolean retireEntityScheduling;

        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) {
            entitySchedules++;
            if (failEntityScheduling) throw new IllegalStateException("entity scheduling unavailable");
            TestTaskHandle handle = new TestTaskHandle();
            entityTasks.add(new EntityTask(target.nativeEntity(), task, handle));
            return handle;
        }

        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task, Runnable retired) {
            if (retireEntityScheduling) {
                retired.run();
                return new TestTaskHandle();
            }
            return runAtEntity(target, task);
        }

        private void runEntityTasks() {
            while (!entityTasks.isEmpty()) {
                EntityTask scheduled = entityTasks.remove();
                if (scheduled.handle.cancelled()) continue;
                inEntityContext = true;
                try { scheduled.task.run(); }
                finally { inEntityContext = false; }
            }
        }

        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) {
            throw new AssertionError("Lunar setting work must use the entity scheduler");
        }
        @Override public TaskHandle runGlobal(Runnable task) {
            throw new AssertionError("Unexpected global scheduler usage");
        }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
            throw new AssertionError("Unexpected delayed scheduler usage");
        }
        @Override public TaskHandle runAsync(Runnable task) {
            throw new AssertionError("Apollo mutation must not run asynchronously");
        }
        @Override public void shutdown() { }
    }

    private record EntityTask(Object target, Runnable task, TestTaskHandle handle) { }

    private static final class TestTaskHandle implements TaskHandle {
        private boolean cancelled;
        @Override public void cancel() { cancelled = true; }
        @Override public boolean cancelled() { return cancelled; }
    }
}
