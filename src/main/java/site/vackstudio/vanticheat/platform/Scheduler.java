package site.vackstudio.vanticheat.platform;

public interface Scheduler {
    TaskHandle runAtEntity(EntityTarget target, Runnable task);
    TaskHandle runAtLocation(RegionTarget target, Runnable task);
    TaskHandle runGlobal(Runnable task);
    TaskHandle runGlobalLater(Runnable task, long delayTicks);
    TaskHandle runAsync(Runnable task);
    void shutdown();
}
