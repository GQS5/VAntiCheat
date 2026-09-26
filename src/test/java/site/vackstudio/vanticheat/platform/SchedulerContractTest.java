package site.vackstudio.vanticheat.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulerContractTest {
    @Test
    void fakeSchedulerExposesAllRequiredExecutionContexts() {
        RecordingScheduler scheduler = new RecordingScheduler();
        scheduler.runAtEntity(new EntityTarget(new Object()), () -> { });
        scheduler.runAtLocation(new RegionTarget(new Object(), 1, -2), () -> { });
        scheduler.runGlobal(() -> { });
        scheduler.runAsync(() -> { });

        assertEquals(4, scheduler.calls);
    }

    private static final class RecordingScheduler implements Scheduler {
        private int calls;

        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { return record(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { return record(); }
        @Override public TaskHandle runGlobal(Runnable task) { return record(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) { return record(); }
        @Override public TaskHandle runAsync(Runnable task) { return record(); }
        @Override public void shutdown() { }

        private TaskHandle record() {
            calls++;
            return new TaskHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }
    }
}
