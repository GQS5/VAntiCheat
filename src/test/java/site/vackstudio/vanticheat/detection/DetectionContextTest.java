package site.vackstudio.vanticheat.detection;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionContextTest {
    @Test
    void contextBindsTargetToSessionAndExposesPlatformBoundary() {
        UUID targetId = UUID.randomUUID();
        DetectionTarget target = new DetectionTarget(targetId, "Player", true);
        DetectionSession session = new DetectionSession(UUID.randomUUID(), targetId, "sample", Instant.now());

        assertDoesNotThrow(() -> new DetectionContext(target, session, context(),
                FoundationConfig.defaults(), Logger.getAnonymousLogger()));
        assertThrows(IllegalArgumentException.class, () -> new DetectionContext(
                new DetectionTarget(UUID.randomUUID(), "Other", true), session, context(),
                FoundationConfig.defaults(), Logger.getAnonymousLogger()));
    }

    private static PlatformContext context() {
        return new PlatformContext(Platform.PAPER, new Scheduler() {
            @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { return handle(); }
            @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { return handle(); }
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
