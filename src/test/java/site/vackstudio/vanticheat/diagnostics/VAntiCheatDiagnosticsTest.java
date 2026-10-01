package site.vackstudio.vanticheat.diagnostics;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.core.VAntiCheatCore;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.detection.probe.ClientProbeTransport;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeRegistry;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;
import site.vackstudio.vanticheat.lunar.LunarClientService;
import site.vackstudio.vanticheat.lunar.LunarPolicyConfig;
import site.vackstudio.vanticheat.platform.AutomaticCheckCoordinator;
import site.vackstudio.vanticheat.platform.ClientPlatformService;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VAntiCheatDiagnosticsTest {
    @Test
    void canonicalSnapshotReportsImmutableRegistryPlatformApolloAndReloadState() {
        TestScheduler scheduler = new TestScheduler();
        VAntiCheatCore core = runningCore(scheduler);
        ClientDetectionConfig config = config();
        CheckHacksClientDetectionModule module = module(config);
        module.start();
        ClientPlatformService platforms = new ClientPlatformService(
                new FakeProvider(false, null), new FakeProvider(false, null));
        platforms.classify(UUID.randomUUID());
        LunarClientService lunar = new LunarClientService(Logger.getAnonymousLogger(), scheduler);
        lunar.start(LunarPolicyConfig.defaults(), null);
        AtomicLong version = new AtomicLong(7);
        AtomicReference<VAntiCheatDiagnostics.Reload> reload = new AtomicReference<>(
                new VAntiCheatDiagnostics.Reload("FAILED", java.time.Instant.now(),
                        "invalid probe entry", 7, true));
        VAntiCheatDiagnostics diagnostics = diagnostics(core, config, module, platforms, lunar,
                scheduler, version, reload);

        VAntiCheatDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(VAntiCheatDiagnostics.Health.HEALTHY, snapshot.health());
        assertEquals(3, snapshot.configuration().totalProbes());
        assertEquals(2, snapshot.configuration().enabledProbes());
        assertEquals(2, snapshot.configuration().manualProbes());
        assertEquals(1, snapshot.configuration().automaticProbes());
        assertEquals(1, snapshot.configuration().verifiedProbes());
        assertEquals(2, snapshot.configuration().unverifiedProbes());
        assertEquals("FAILED", snapshot.configuration().lastReload().state());
        assertTrue(snapshot.configuration().lastReload().lastKnownGoodActive());
        assertEquals(ClientPlatformService.ProviderState.NOT_PRESENT, snapshot.platform().providers().floodgate());
        assertEquals(1, snapshot.platform().noProviderCached());
        assertEquals("NOT_PRESENT", snapshot.lunar().integration());
        assertEquals("READY", snapshot.platform().providers().readiness().name());
        assertTrue(snapshot.results().values().stream().allMatch(count -> count == 0));
    }

    @Test
    void healthIsDerivedFromEngineAvailabilityAndOptionalIntegrationsDoNotDegradeIt() {
        TestScheduler scheduler = new TestScheduler();
        VAntiCheatCore core = runningCore(scheduler);
        ClientDetectionConfig config = config();
        ClientDetectionConfig disabled = new ClientDetectionConfig(false, true, 40, 0,
                ProbeRegistry.empty(), false, 0, false, 8);
        ClientPlatformService providersMissing = new ClientPlatformService(
                new FakeProvider(false, null), new FakeProvider(false, null));
        VAntiCheatDiagnostics disabledDetection = diagnostics(core, disabled, null, providersMissing,
                null, scheduler, new AtomicLong(0), new AtomicReference<>());
        assertEquals(VAntiCheatDiagnostics.Health.DEGRADED, disabledDetection.snapshot().health());

        VAntiCheatDiagnostics unavailableCore = diagnostics(null, config, null, providersMissing,
                null, scheduler, new AtomicLong(0), new AtomicReference<>());
        assertEquals(VAntiCheatDiagnostics.Health.ERROR, unavailableCore.snapshot().health());

        ClientDetectionConfig emptyRegistry = new ClientDetectionConfig(true, true, 40, 0,
                ProbeRegistry.empty(), false, 0, false, 8);
        CheckHacksClientDetectionModule emptyModule = module(emptyRegistry);
        emptyModule.start();
        assertEquals(VAntiCheatDiagnostics.Health.DEGRADED,
                diagnostics(core, emptyRegistry, emptyModule, providersMissing, null, scheduler,
                        new AtomicLong(1), new AtomicReference<>()).snapshot().health());

        VAntiCheatDiagnostics invalidConfig = new VAntiCheatDiagnostics("test-version", "PAPER", core,
                () -> config, () -> false, null, () -> null, providersMissing, null,
                TrustedPlayerService.NONE, () -> 0,
                new AtomicReference<>(new VAntiCheatDiagnostics.Reload("FAILED", java.time.Instant.now(),
                        "invalid config", 0, false)));
        assertEquals(VAntiCheatDiagnostics.Health.ERROR, invalidConfig.snapshot().health());

        CheckHacksClientDetectionModule module = module(config);
        module.start();
        assertEquals(VAntiCheatDiagnostics.Health.HEALTHY,
                diagnostics(core, config, module, providersMissing, null, scheduler,
                        new AtomicLong(1), new AtomicReference<>()).snapshot().health());
    }

    @Test
    void registryAndSnapshotsAreReadOnlyAndReloadVersionIsLive() {
        TestScheduler scheduler = new TestScheduler();
        ClientDetectionConfig config = config();
        CheckHacksClientDetectionModule module = module(config);
        module.start();
        AtomicLong version = new AtomicLong(1);
        AtomicReference<VAntiCheatDiagnostics.Reload> reload = new AtomicReference<>(
                new VAntiCheatDiagnostics.Reload("SUCCESS", java.time.Instant.now(), "loaded", 1, true));
        VAntiCheatDiagnostics diagnostics = diagnostics(runningCore(scheduler), config, module,
                new ClientPlatformService(new FakeProvider(false, null), new FakeProvider(false, null)),
                null, scheduler, version, reload);

        assertEquals(3, diagnostics.probeRegistry().size());
        VAntiCheatDiagnostics.Snapshot first = diagnostics.snapshot();
        version.set(2);
        reload.set(new VAntiCheatDiagnostics.Reload("FAILED", java.time.Instant.now(),
                "invalid replacement", 2, true));
        VAntiCheatDiagnostics.Snapshot next = diagnostics.snapshot();
        assertEquals(1, first.configuration().registryVersion());
        assertEquals(2, next.configuration().registryVersion());
        assertEquals("FAILED", next.configuration().lastReload().state());
        assertTrue(next.configuration().lastReload().lastKnownGoodActive());
        assertThrows(UnsupportedOperationException.class,
                () -> first.results().put("ERROR", 99L));
    }

    private static VAntiCheatDiagnostics diagnostics(VAntiCheatCore core, ClientDetectionConfig config,
                                                       CheckHacksClientDetectionModule module,
                                                       ClientPlatformService platforms, LunarClientService lunar,
                                                       TestScheduler scheduler, AtomicLong version,
                                                       AtomicReference<VAntiCheatDiagnostics.Reload> reload) {
        AutomaticCheckCoordinator automatic = new AutomaticCheckCoordinator(scheduler,
                config.autoCheckDelayTicks(), config.firstJoinOnly(), config.maxConcurrentAutoChecks(),
                Logger.getAnonymousLogger());
        return new VAntiCheatDiagnostics("test-version", "PAPER", core, () -> config, () -> true, module,
                automatic::metrics, platforms, lunar, TrustedPlayerService.NONE, version::get, reload);
    }

    private static VAntiCheatCore runningCore(TestScheduler scheduler) {
        VAntiCheatCore core = new VAntiCheatCore(FoundationConfig.defaults(),
                new PlatformContext(Platform.PAPER, scheduler), Logger.getAnonymousLogger());
        core.start();
        return core;
    }

    private static CheckHacksClientDetectionModule module(ClientDetectionConfig config) {
        return new CheckHacksClientDetectionModule(config, new ClientProbeTransport() {
            @Override public site.vackstudio.vanticheat.detection.probe.ProbeHandle send(
                    site.vackstudio.vanticheat.detection.probe.ProbeRequest request,
                    java.util.function.Consumer<site.vackstudio.vanticheat.detection.probe.ProbeResponse> response) {
                throw new UnsupportedOperationException();
            }
            @Override public void stop() { }
        });
    }

    private static ClientDetectionConfig config() {
        return new ClientDetectionConfig(true, true, 40, 0, List.of(
                probe("one", true, true, true, ProbeVerificationStatus.VERIFIED),
                probe("two", true, true, false, ProbeVerificationStatus.UNVERIFIED),
                probe("three", false, true, true, ProbeVerificationStatus.UNVERIFIED)),
                true, 1, false, List.of(), 8);
    }

    private static ProbeDefinition probe(String id, boolean enabled, boolean manual, boolean automatic,
                                         ProbeVerificationStatus verification) {
        return new ProbeDefinition(id, id, "key." + id, ProbeMode.TRANSLATE, "fallback", enabled,
                manual, automatic, verification);
    }

    private static final class FakeProvider implements ClientPlatformService.Provider {
        private final boolean installed;
        private final Boolean answer;
        private FakeProvider(boolean installed, Boolean answer) { this.installed = installed; this.answer = answer; }
        @Override public String name() { return "stub"; }
        @Override public boolean installed() { return installed; }
        @Override public boolean ready() { return installed && answer != null; }
        @Override public Boolean isBedrock(UUID id) { return answer; }
    }

    private static final class TestScheduler implements Scheduler {
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { throw new AssertionError(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { throw new AssertionError(); }
        @Override public TaskHandle runGlobal(Runnable task) { throw new AssertionError(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) { throw new AssertionError(); }
        @Override public TaskHandle runAsync(Runnable task) { throw new AssertionError(); }
        @Override public void shutdown() { }
    }
}
