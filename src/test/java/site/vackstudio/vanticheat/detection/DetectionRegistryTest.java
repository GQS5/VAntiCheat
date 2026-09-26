package site.vackstudio.vanticheat.detection;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionRegistryTest {
    @Test
    void registersLooksUpUnregistersAndRejectsDuplicates() {
        DetectionRegistry registry = new DetectionRegistry();
        TestModule module = new TestModule("sample");
        registry.register(module);

        assertEquals(module, registry.lookup("sample"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new TestModule("sample")));
        assertEquals(module, registry.unregister("sample"));
        assertEquals(List.of(), registry.activeModules());
    }

    @Test
    void disabledModulesAreNotRegistered() {
        DetectionRegistry registry = new DetectionRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new TestModule("disabled", false)));
    }

    @Test
    void concurrentRegistrationKeepsAllUniqueModules() throws Exception {
        DetectionRegistry registry = new DetectionRegistry();
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        List<Runnable> work = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int id = i;
            work.add(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                registry.register(new TestModule("sample-" + id));
            });
        }
        var futures = work.stream().map(executor::submit).toList();
        for (var future : futures) future.get();
        executor.shutdownNow();
        assertEquals(8, registry.activeModules().size());
    }

    @Test
    void emptyRegistryInitializesAndStops() {
        DetectionRegistry registry = new DetectionRegistry();
        registry.initializeAll(context());
        registry.startAll();
        registry.stopAll();
        registry.stopAll();
        assertEquals(List.of(), registry.activeModules());
    }

    @Test
    void moduleLifecycleRunsInOrder() {
        DetectionRegistry registry = new DetectionRegistry();
        TestModule module = new TestModule("sample");
        registry.register(module);
        registry.initializeAll(context());
        registry.startAll();
        registry.stopAll();

        assertEquals(List.of("initialize", "start", "stop"), module.calls);
    }

    private static DetectionModuleContext context() {
        return new DetectionModuleContext(new PlatformContext(Platform.PAPER, new NoopScheduler()),
                FoundationConfig.defaults(), Logger.getAnonymousLogger());
    }

    private static DetectionDefinition definition(String id, boolean enabled) {
        return new DetectionDefinition(id, "Sample", "Generic test definition",
                DetectionCategory.CLIENT, DetectionSeverity.INFO, enabled);
    }

    private static final class TestModule implements DetectionModule {
        private final DetectionDefinition definition;
        private final List<String> calls = new ArrayList<>();

        private TestModule(String id) { this(id, true); }
        private TestModule(String id, boolean enabled) { this.definition = DetectionRegistryTest.definition(id, enabled); }
        @Override public DetectionDefinition definition() { return definition; }
        @Override public String version() { return "test"; }
        @Override public void initialize(DetectionModuleContext context) { calls.add("initialize"); }
        @Override public void start() { calls.add("start"); }
        @Override public void stop() { calls.add("stop"); }
    }

    private static final class NoopScheduler implements Scheduler {
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
    }
}
