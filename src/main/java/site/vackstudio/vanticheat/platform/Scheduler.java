package site.vackstudio.vanticheat.platform;

public interface Scheduler {
    TaskHandle runAtEntity(EntityTarget target, Runnable task);
    /** Schedules entity work and reports retirement without running entity work off-context. */
    default TaskHandle runAtEntity(EntityTarget target, Runnable task, Runnable retired) {
        return runAtEntity(target, task);
    }
    TaskHandle runAtLocation(RegionTarget target, Runnable task);
    TaskHandle runGlobal(Runnable task);
    TaskHandle runGlobalLater(Runnable task, long delayTicks);
    TaskHandle runAsync(Runnable task);
    void shutdown();
}
