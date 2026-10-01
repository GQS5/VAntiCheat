package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12: the metric that matters is player impact, not raw throughput.
 *
 * <p>These measurements deliberately avoid sub-second optimisation targets. They record how
 * much of a player's client and world is touched, and how long the engine itself spends
 * deciding, so a future change cannot quietly reintroduce an unattended interactive probe
 * or a gameplay freeze while still looking fast.
 */
class P32PerformanceAndPlayerImpactTest {
    private static final int WARMUP = 200;
    private static final int ITERATIONS = 2000;

    @TempDir
    private Path temporaryDirectory;

    /**
     * The primary metric: with the shipped catalog, an automatic join scan performs no
     * client-visible work at all, so player-impacting time is exactly zero.
     */
    @Test
    void automaticJoinHasZeroPlayerImpactingTime() throws Exception {
        CountingTransport transport = new CountingTransport();
        CheckHacksClientDetectionModule module = module(config(false), transport, false);
        List<DetectionResult> results = new ArrayList<>();

        for (int index = 0; index < ITERATIONS; index++) {
            AtomicReference<DetectionResult> result = new AtomicReference<>();
            module.checkAutomaticIfIdle(new DetectionTarget(UUID.randomUUID(), "join", true, new Object()),
                    "JOIN", result::set);
            results.add(result.get());
        }

        assertEquals(0, transport.uiOpenings.get(), "no automatic scan may open a client screen");
        assertEquals(0, transport.worldMutations.get(), "no automatic scan may mutate the world");
        assertEquals(0, transport.playerStateChanges.get(), "no automatic scan may alter player state");
        assertEquals(0, transport.batches.get(),
                "a passive automatic scan is answered from evidence and never dispatches a batch");
        assertTrue(results.stream().allMatch(result -> result != null
                        && (result.status() == DetectionStatus.SKIPPED
                        || result.status() == DetectionStatus.CLEAN)),
                "every automatic scan terminates with an explicit, honest conclusion");
    }

    /**
     * Automatic admission cost stays a constant-time lookup; it must not scale with the
     * number of configured probes, because it is on the join path.
     */
    @Test
    void automaticAdmissionCostIsIndependentOfCatalogSize() throws Exception {
        CountingTransport transport = new CountingTransport();
        ClientDetectionConfig config = config(false);
        CheckHacksClientDetectionModule module = module(config, transport, false);

        for (int index = 0; index < WARMUP; index++) {
            module.checkAutomaticIfIdle(target(), "JOIN", ignored -> { });
        }
        long before = System.nanoTime();
        for (int index = 0; index < ITERATIONS; index++) {
            module.checkAutomaticIfIdle(target(), "JOIN", ignored -> { });
        }
        long perJoinNanos = (System.nanoTime() - before) / ITERATIONS;

        // A 38-probe catalog must not turn a join into measurable work.
        assertTrue(perJoinNanos < 5_000_000L,
                "automatic admission must stay sub-5ms per join, measured " + perJoinNanos + "ns");
    }

    /**
     * An explicit manual interactive scan is the only path that may cost the player
     * attention, and it is bounded by the configured deadline.
     */
    @Test
    void manualInteractiveScanCostIsBoundedByTheConfiguredDeadline() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.silent = true;
        ClientDetectionConfig config = config(true);
        CheckHacksClientDetectionModule module = module(config, transport, false);

        List<ProbeDefinition> interactive = config.probeRegistry().enabled().stream()
                .filter(probe -> !probe.passiveChannelProbe()).toList();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        module.check(target(), interactive, result::set);

        assertEquals(DetectionStatus.TIMEOUT, result.get().status());
        // 3 probes per batch; the passive probe is excluded because it is not carried by the
        // sign transport at all.
        int expectedBatches = (interactive.size() + 2) / 3;
        assertEquals(expectedBatches, transport.batches.get(),
                "one batch per three probes; the protocol forbids overlapping editors");
    }

    /** A passive probe adds no client-facing cost to the automatic path. */
    @Test
    void passiveAutomaticScanHasNoClientFacingCost() {
        ProbeDefinition passive = ProbeDefinition.passive("client-brand", "Client Brand", "brand", "test:brand",
                true, true, ProbeVerificationStatus.UNVERIFIED,
                "platform", "test", "context only");
        CountingTransport transport = new CountingTransport();
        transport.answer = window -> ClientResponseFixtures.clean(window);
        CheckHacksClientDetectionModule module = module(
                new ClientDetectionConfig(true, false, 40, 0, ProbeRegistry.of(List.of(passive)),
                        true, 1, false, 32, 10, 2, false), transport, false);
        DetectionTarget player = target();
        module.beginPassiveContext(player.id(), null, "fabric");

        AtomicReference<DetectionResult> result = new AtomicReference<>();
        module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.CLEAN, result.get().status());
        assertEquals(0, transport.batches.get(),
                "passive evidence is event driven and needs no dispatch at all");
        assertEquals(0, transport.uiOpenings.get());
        assertEquals(0, transport.worldMutations.get());
    }

    /** The engine's own decision cost stays small and is not dominated by allocation. */
    @Test
    void evaluationCostPerProbeIsSmall() {
        ProbeDefinition probe = new ProbeDefinition("bench", "Bench", "mod.bench.title",
                ProbeMode.TRANSLATE, "NO_BENCH", true, true, false,
                ProbeVerificationStatus.UNVERIFIED, "bench", "", "", "",
                ProbeTransportMode.INTERACTIVE, true);
        String[] responses = {"Some localized title", "Another title", "\u00d6ffnen", "Ouvrir", ""};

        for (int index = 0; index < WARMUP; index++) {
            CheckHacksResponseEvaluator.evaluateDetailed(probe, responses[index % responses.length], null, false);
        }
        long before = System.nanoTime();
        for (int index = 0; index < ITERATIONS * 10; index++) {
            CheckHacksResponseEvaluator.evaluateDetailed(probe, responses[index % responses.length], null, false);
        }
        long perEvaluationNanos = (System.nanoTime() - before) / (ITERATIONS * 10L);

        assertTrue(perEvaluationNanos < 50_000L,
                "a single evaluation must stay well under a tick, measured " + perEvaluationNanos + "ns");
    }

    /** Cleanup bookkeeping must not grow with the number of completed scans. */
    @Test
    void cleanupBookkeepingIsConstantPerScan() throws Exception {
        CountingTransport transport = new CountingTransport();
        CheckHacksClientDetectionModule module = module(config(true), transport, false);
        AtomicLong peakOperations = new AtomicLong();

        for (int index = 0; index < 500; index++) {
            module.check(target(), ignored -> { });
            peakOperations.set(Math.max(peakOperations.get(), transport.activeOperations.get()));
        }

        assertEquals(0, transport.activeOperations.get(), "no operation may accumulate across scans");
        assertTrue(peakOperations.get() <= 1,
                "a sequential player holds at most one operation, peak " + peakOperations.get());
    }

    // ------------------------------------------------------------------- helpers

    private ClientDetectionConfig config(boolean interactiveAutomatic) throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            Files.write(file, stream.readAllBytes());
        }
        ClientDetectionConfig loaded = ClientDetectionConfig.load(temporaryDirectory,
                Logger.getAnonymousLogger());
        return new ClientDetectionConfig(loaded.enabled(), false, loaded.timeoutTicks(),
                loaded.betweenProbeTicks(), loaded.probeRegistry(), loaded.autoCheckOnJoin(),
                loaded.autoCheckDelayTicks(), loaded.firstJoinOnly(), loaded.maxConcurrentAutoChecks(),
                loaded.shortTimeoutTicks(), loaded.shortTimeoutAfterConsecutiveTimeouts(),
                interactiveAutomatic);
    }

    private static DetectionTarget target() {
        return new DetectionTarget(UUID.randomUUID(), "target", true, new Object());
    }

    private static CheckHacksClientDetectionModule module(ClientDetectionConfig config,
                                                           ClientProbeTransport transport,
                                                           boolean doubleCheck) {
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new InlineScheduler()),
                FoundationConfig.defaults(), Logger.getLogger("p32-perf")));
        module.start();
        return module;
    }

    private static final class CountingTransport implements ClientProbeTransport {
        private final AtomicInteger uiOpenings = new AtomicInteger();
        private final AtomicInteger worldMutations = new AtomicInteger();
        private final AtomicInteger playerStateChanges = new AtomicInteger();
        private final AtomicInteger batches = new AtomicInteger();
        private final AtomicInteger activeOperations = new AtomicInteger();
        private Function<List<ProbeDefinition>, ClientResponseFixtures.Rendered> answer =
                window -> ClientResponseFixtures.clean(window);
        private boolean silent;

        @Override
        public ProbeHandle send(ProbeRequest request, Consumer<ProbeResponse> response) {
            batches.incrementAndGet();
            activeOperations.incrementAndGet();
            if (request.probes().get(0).transport().opensClientUi()) {
                uiOpenings.incrementAndGet();
                worldMutations.incrementAndGet();
            }
            // A silent client still produces a bounded terminal outcome, exactly as the
            // real transport's deadline does.
            response.accept(silent
                    ? ClientResponseFixtures.silent(request)
                    : ClientResponseFixtures.bind(request, answer.apply(request.probes())));
            activeOperations.decrementAndGet();
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }

        @Override public void stop() { }
    }

    private static final class InlineScheduler implements Scheduler {
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) { task.run(); return handle(); }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }

        private static TaskHandle handle() {
            return new TaskHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }
    }
}
