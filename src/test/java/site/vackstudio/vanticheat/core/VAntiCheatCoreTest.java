package site.vackstudio.vanticheat.core;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.lifecycle.LifecycleState;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VAntiCheatCoreTest {
    @Test
    void lifecycleStartsAndStopsExactlyOnce() {
        VAntiCheatCore core = new VAntiCheatCore(FoundationConfig.defaults(), context(), Logger.getAnonymousLogger());

        assertEquals(LifecycleState.NEW, core.state());
        core.start();
        assertEquals(LifecycleState.RUNNING, core.state());
        assertThrows(IllegalStateException.class, core::start);
        core.shutdown();
        assertEquals(LifecycleState.STOPPED, core.state());
        core.shutdown();
        assertEquals(LifecycleState.STOPPED, core.state());
        assertThrows(IllegalStateException.class, core::start);
    }

    @Test
    void coreRetainsConfigurationAndPlatform() {
        FoundationConfig config = new FoundationConfig(false, true, true);
        VAntiCheatCore core = new VAntiCheatCore(config, context(), Logger.getAnonymousLogger());

        assertEquals(config, core.config());
        assertEquals(Platform.PAPER, core.platform());
    }

    private static PlatformContext context() {
        return new PlatformContext(Platform.PAPER, new Scheduler() {
            @Override public TaskHandle runAtEntity(site.vackstudio.vanticheat.platform.EntityTarget target, Runnable task) { return handle(); }
            @Override public TaskHandle runAtLocation(site.vackstudio.vanticheat.platform.RegionTarget target, Runnable task) { return handle(); }
            @Override public TaskHandle runGlobal(Runnable task) { return handle(); }
            @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) { return handle(); }
            @Override public TaskHandle runAsync(Runnable task) { return handle(); }
            @Override public void shutdown() { }
            private TaskHandle handle() { return new TaskHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            }; }
        });
    }
}
