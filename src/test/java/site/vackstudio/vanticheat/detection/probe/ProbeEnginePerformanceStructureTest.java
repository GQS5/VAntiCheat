package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic structural model; it does not measure a client, server, or network. */
class ProbeEnginePerformanceStructureTest {
    private static final List<Integer> SCAN_SIZES = List.of(1, 3, 10, 30, 38, 50, 75, 100);
    private static final int RESPONSE_TIMEOUT_TICKS = 40;

    @Test
    void legacyBetweenTickStructuralBaselineForRepresentativeScanSizes() {
        System.out.println("STRUCTURAL / MOCKED baseline (between-probe-ticks=1):");
        System.out.println("probes,batches,schedulerCalls,entity,region,global,delayedTasks,batchDelays,openOrderDelays,signOps,blockPackets,openPackets,cleanup,timeouts,worldLookups,components,candidateClones");
        for (int probeCount : SCAN_SIZES) {
            StructuralRun run = runScan(probeCount, 1, 10, false);
            int batches = batches(probeCount);
            assertEquals(DetectionStatus.CLEAN, run.result().status());
            assertEquals(batches, run.metrics().transportSends);
            assertEquals(batches, run.metrics().signPlacements);
            assertEquals(batches, run.metrics().blockEntityPackets);
            assertEquals(batches, run.metrics().openSignPackets);
            assertEquals(batches, run.metrics().cleanupOperations);
            assertEquals(batches, run.metrics().timeoutTasksScheduled);
            assertEquals(batches, run.metrics().timeoutTasksCancelled);
            assertEquals(8 * batches - 1, run.scheduler().totalCalls());
            assertEquals(batches - 1, run.scheduler().positiveBatchDelayCalls);
            printRow(probeCount, batches, run);
        }
    }

    @Test
    void zeroInterBatchDelayRemovesTheDelayAndFinalBatchWork() {
        for (int probeCount : SCAN_SIZES) {
            StructuralRun run = runScan(probeCount, 0, 10, true);
            int batches = batches(probeCount);
            assertEquals(DetectionStatus.CLEAN, run.result().status());
            assertEquals(batches, run.metrics().transportSends);
            assertEquals(0, run.scheduler().zeroBatchDelayCalls);
            assertEquals(0, run.scheduler().positiveBatchDelayCalls);
            assertEquals(7 * batches, run.scheduler().totalCalls());
        }
    }

    @Test
    void comparesLegacyAndOptimizedStructuralBudgetsForTenRepetitions() {
        System.out.println("STRUCTURAL / MOCKED comparison (10 repetitions; model counts, not live latency):");
        System.out.println("side,probes,batches,schedulerCalls,entity,region,global,delayedTasks,batchDelays,openOrderDelays,signOps,blockPackets,openPackets,cleanup,timeouts,worldLookups,components,candidateClones");
        for (int probeCount : SCAN_SIZES) {
            StructuralRun before = runScan(probeCount, 1, 10, false);
            StructuralRun after = runScan(probeCount, 0, 10, true);
            System.out.print("BEFORE,");
            printRow(probeCount, batches(probeCount), before);
            System.out.print("AFTER,");
            printRow(probeCount, batches(probeCount), after);
            assertEquals(batches(probeCount), before.metrics().transportSends);
            assertEquals(batches(probeCount), after.metrics().transportSends);
            if (batches(probeCount) > 1) {
                assertTrue(after.scheduler().totalCalls() < before.scheduler().totalCalls());
            } else {
                assertEquals(before.scheduler().totalCalls(), after.scheduler().totalCalls());
            }
            assertTrue(after.metrics().candidateLocationClones < before.metrics().candidateLocationClones);
            assertTrue(after.metrics().worldBlockLookups < before.metrics().worldBlockLookups);
            assertTrue(after.metrics().componentsBuilt < before.metrics().componentsBuilt);
        }
    }

    private static StructuralRun runScan(int probeCount, long betweenProbeTicks, int iterations,
                                         boolean optimizedTransport) {
        StructuralRun first = null;
        for (int iteration = 0; iteration < iterations; iteration++) {
            StructuralMetrics metrics = new StructuralMetrics();
            StructuralScheduler scheduler = new StructuralScheduler(RESPONSE_TIMEOUT_TICKS);
            StructuralTransport transport = new StructuralTransport(scheduler, metrics, optimizedTransport);
            CheckHacksClientDetectionModule module = module(probeCount, betweenProbeTicks, transport, scheduler);
            AtomicReference<DetectionResult> result = new AtomicReference<>();
            module.check(new DetectionTarget(UUID.randomUUID(), "structural", true, new Object()), result::set);
            StructuralRun run = new StructuralRun(result.get(), metrics, scheduler);
            if (first == null) first = run;
            else {
                assertEquals(first.result().status(), run.result().status());
                assertEquals(schedulerSnapshot(first.scheduler()), schedulerSnapshot(scheduler));
                assertEquals(metricsSnapshot(first.metrics()), metricsSnapshot(metrics));
            }
        }
        return first;
    }

    private static CheckHacksClientDetectionModule module(int count, long betweenProbeTicks,
                                                           ClientProbeTransport transport,
                                                           StructuralScheduler scheduler) {
        List<ProbeDefinition> probes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            probes.add(new ProbeDefinition("probe-" + i, "Probe " + i, "key.probe." + i,
                    ProbeMode.TRANSLATE, "fallback.probe." + i, true,
                    true, true, ProbeVerificationStatus.VERIFIED));
        }
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(
                new ClientDetectionConfig(true, true, RESPONSE_TIMEOUT_TICKS, betweenProbeTicks, probes), transport);
        module.initialize(new DetectionModuleContext(new PlatformContext(Platform.PAPER, scheduler),
                FoundationConfig.defaults(), Logger.getLogger("performance-test")));
        module.start();
        return module;
    }

    private static int batches(int probes) { return (probes + 2) / 3; }

    private static List<Integer> schedulerSnapshot(StructuralScheduler scheduler) {
        return List.of(scheduler.entityCalls, scheduler.regionCalls, scheduler.globalCalls,
                scheduler.delayedCalls, scheduler.positiveBatchDelayCalls,
                scheduler.zeroBatchDelayCalls, scheduler.openOrderDelayCalls);
    }

    private static List<Integer> metricsSnapshot(StructuralMetrics metrics) {
        return List.of(metrics.transportSends, metrics.signPlacements, metrics.signUpdates,
                metrics.blockEntityPackets, metrics.openSignPackets, metrics.blockChangePackets,
                metrics.cleanupOperations, metrics.timeoutTasksScheduled, metrics.timeoutTasksCancelled,
                metrics.candidateAttempts, metrics.candidateLocationClones, metrics.worldBlockLookups,
                metrics.blockTypeReads, metrics.blockStateReads, metrics.blockMutations,
                metrics.blockStateUpdates, metrics.componentsBuilt);
    }

    private static void printRow(int probes, int batches, StructuralRun run) {
        StructuralScheduler scheduler = run.scheduler();
        StructuralMetrics metrics = run.metrics();
        System.out.println(probes + "," + batches + "," + scheduler.totalCalls()
                + "," + scheduler.entityCalls + "," + scheduler.regionCalls + ","
                + scheduler.globalCalls + "," + scheduler.delayedCalls + ","
                + scheduler.positiveBatchDelayCalls + "," + scheduler.openOrderDelayCalls + ","
                + metrics.signPlacements + "," + metrics.blockEntityPackets + ","
                + metrics.openSignPackets + "," + metrics.cleanupOperations + ","
                + metrics.timeoutTasksScheduled + "," + metrics.worldBlockLookups + ","
                + metrics.componentsBuilt + "," + metrics.candidateLocationClones);
    }

    private record StructuralRun(DetectionResult result, StructuralMetrics metrics,
                                 StructuralScheduler scheduler) { }

    private static final class StructuralMetrics {
        private int transportSends;
        private int signPlacements;
        private int signUpdates;
        private int blockEntityPackets;
        private int openSignPackets;
        private int blockChangePackets;
        private int cleanupOperations;
        private int timeoutTasksScheduled;
        private int timeoutTasksCancelled;
        private int candidateAttempts;
        private int candidateLocationClones;
        private int worldBlockLookups;
        private int blockTypeReads;
        private int blockStateReads;
        private int blockMutations;
        private int blockStateUpdates;
        private int componentsBuilt;
    }

    /** Models the current successful-first-candidate Paper transport operation structure. */
    private static final class StructuralTransport implements ClientProbeTransport {
        private final StructuralScheduler scheduler;
        private final StructuralMetrics metrics;
        private final boolean optimized;

        private StructuralTransport(StructuralScheduler scheduler, StructuralMetrics metrics,
                                    boolean optimized) {
            this.scheduler = scheduler;
            this.metrics = metrics;
            this.optimized = optimized;
        }

        @Override
        public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            metrics.transportSends++;
            scheduler.runAtEntity(new EntityTarget(request.target().platformHandle()), () -> {
                    // Count cloned candidates plus the barrier-location clone on the chosen path.
                    metrics.candidateLocationClones += optimized ? 2 : 19;
                    // Legacy path rebuilt two nine-location lists for size/get, plus below clone.
                    scheduler.runAtLocation(new RegionTarget(new Object(), 0, 0), () -> {
                        metrics.candidateAttempts++;
                        metrics.worldBlockLookups += optimized ? 3 : 5;
                        // player base(s), candidate, barrier check, and legacy second barrier lookup
                    metrics.blockTypeReads += 2;
                    metrics.blockStateReads += 2; // original block + placed sign state
                    metrics.blockMutations += 2; // barrier + sign placement
                    metrics.signPlacements++;
                    metrics.signUpdates++;
                    metrics.componentsBuilt += request.probes().size() + (optimized ? 0 : 1);
                    scheduler.runAtEntity(new EntityTarget(request.target().platformHandle()), () -> {
                        metrics.blockEntityPackets++;
                        scheduler.runOpenEditorDelay(() -> scheduler.runAtEntity(
                                new EntityTarget(request.target().platformHandle()), () -> {
                                    metrics.openSignPackets++;
                                    metrics.blockChangePackets++;
                                }));
                    });
                    scheduler.runGlobalLater(() -> { }, request.timeoutTicks());
                    metrics.timeoutTasksScheduled++;
                    callback.accept(new ProbeResponse(request.sessionId(), request.target().id(),
                            responseLines(request), ProbeResponse.Outcome.RESPONSE));
                    scheduler.runAtLocation(new RegionTarget(new Object(), 0, 0), () -> {
                        metrics.cleanupOperations++;
                        metrics.worldBlockLookups += 2; // restore target + barrier
                        metrics.blockMutations++;
                        metrics.blockStateUpdates++;
                    });
                    metrics.timeoutTasksCancelled++;
                });
            });
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }

        private static List<String> responseLines(ProbeRequest request) {
            List<String> lines = new ArrayList<>(request.probes().stream().map(ProbeDefinition::fallback).toList());
            while (lines.size() < 3) lines.add("");
            lines.add(CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY);
            return lines;
        }

        @Override public void stop() { }
    }

    private static final class StructuralScheduler implements Scheduler {
        private final int timeoutTicks;
        private int entityCalls;
        private int regionCalls;
        private int globalCalls;
        private int delayedCalls;
        private int positiveBatchDelayCalls;
        private int zeroBatchDelayCalls;
        private int openOrderDelayCalls;
        private boolean openingEditor;

        private StructuralScheduler(int timeoutTicks) { this.timeoutTicks = timeoutTicks; }

        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) {
            entityCalls++;
            task.run();
            return handle();
        }

        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) {
            regionCalls++;
            task.run();
            return handle();
        }

        @Override public TaskHandle runGlobal(Runnable task) {
            globalCalls++;
            task.run();
            return handle();
        }

        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
            if (delayTicks == 0) {
                zeroBatchDelayCalls++;
                return runGlobal(task);
            }
            delayedCalls++;
            if (delayTicks == timeoutTicks) return handle(); // timer is scheduled then response cancels it
            if (openingEditor) openOrderDelayCalls++;
            else positiveBatchDelayCalls++;
            task.run();
            return handle();
        }

        private void runOpenEditorDelay(Runnable task) {
            openingEditor = true;
            try { runGlobalLater(task, 1); }
            finally { openingEditor = false; }
        }

        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }
        private int totalCalls() { return entityCalls + regionCalls + globalCalls + delayedCalls; }

        private static TaskHandle handle() {
            return new TaskHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }
    }
}
