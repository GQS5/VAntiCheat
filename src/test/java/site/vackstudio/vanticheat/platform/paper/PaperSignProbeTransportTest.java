package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeHandle;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeRequest;
import site.vackstudio.vanticheat.detection.probe.ProbeResponse;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperSignProbeTransportTest {
    @Test
    void candidatesAvoidSolidGroundBelowVanillaPlayers() {
        Location playerFeet = new Location(null, 0, 64, 0);

        assertTrue(PaperSignProbeTransport.candidates(playerFeet).stream()
                .allMatch(candidate -> candidate.getBlockY() > playerFeet.getBlockY()));
    }

    @Test
    void firstCandidateIsDirectlyAbovePlayer() {
        Location playerFeet = new Location(null, 10, 64, -4);

        Location candidate = PaperSignProbeTransport.candidates(playerFeet).get(0);

        assertEquals(playerFeet.getBlockX(), candidate.getBlockX());
        assertEquals(playerFeet.getBlockY() + 4, candidate.getBlockY());
        assertEquals(playerFeet.getBlockZ(), candidate.getBlockZ());
    }

    @Test
    void calculatesChunkCoordinatesWithoutRetrievingChunks() {
        assertEquals(-2, PaperSignProbeTransport.chunkCoordinate(-17));
        assertEquals(-1, PaperSignProbeTransport.chunkCoordinate(-1));
        assertEquals(-1, PaperSignProbeTransport.chunkCoordinate(-16));
        assertEquals(0, PaperSignProbeTransport.chunkCoordinate(15));
        assertEquals(1, PaperSignProbeTransport.chunkCoordinate(16));
    }

    @Test
    void signPacketOrderRequiresUpdatedSignThenBlockEntityThenOpenEditor() {
        PaperSignProbeTransport.PacketOrder order = new PaperSignProbeTransport.PacketOrder();

        assertFalse(order.canSendBlockEntity());
        assertFalse(order.canOpenEditor());
        assertThrows(IllegalStateException.class, order::blockEntitySent);

        order.signUpdated();
        assertEquals(PaperSignProbeTransport.PacketStage.SIGN_UPDATED, order.stage());
        assertFalse(order.canOpenEditor());
        order.blockEntitySent();
        assertEquals(PaperSignProbeTransport.PacketStage.BLOCK_ENTITY_SENT, order.stage());
        order.openEditorSent();
        assertEquals(PaperSignProbeTransport.PacketStage.OPEN_EDITOR_SENT, order.stage());
        assertThrows(IllegalStateException.class, order::openEditorSent);
    }

    @Test
    void placementAndPacketFailuresTerminalizeAndRestoreCapturedState() {
        for (Failure failure : List.of(Failure.BARRIER, Failure.SIGN_PLACE, Failure.SIGN_UPDATE,
                Failure.PACKET_CREATE, Failure.BLOCK_ENTITY_PACKET, Failure.OPEN_EDITOR_PACKET,
                Failure.BLOCK_CHANGE_PACKET)) {
            Harness harness = new Harness(failure, Failure.NONE);

            harness.send();
            harness.runPendingGlobal();

            assertEquals(1, harness.callbacks.get(), failure.toString());
            assertEquals(ProbeResponse.Outcome.ERROR, harness.outcome.get(), failure.toString());
            assertEquals(0, harness.transport.activeOperations(), failure.toString());
            assertEquals(1, harness.world.restores.get(), failure + " restores sign state");
            assertEquals(1, harness.world.barrierRestores.get(), failure + " restores barrier");
            assertEquals(0, harness.packets.openCount.get() > 0 && failure == Failure.BLOCK_ENTITY_PACKET ? 1 : 0,
                    "failed block-entity packet must not progress to editor");
        }
    }

    @Test
    void failuresBeforeTemporaryMutationDoNotOverwriteUncapturedBlock() {
        for (Failure failure : List.of(Failure.CANDIDATE_LOOKUP, Failure.AIR_CHECK, Failure.CAPTURE)) {
            Harness harness = new Harness(failure, Failure.NONE);
            harness.send();

            assertEquals(1, harness.callbacks.get(), failure.toString());
            assertEquals(ProbeResponse.Outcome.ERROR, harness.outcome.get(), failure.toString());
            assertEquals(0, harness.transport.activeOperations(), failure.toString());
            assertEquals(0, harness.world.unsafeAirRestores.get(), failure + " must not erase unknown state");
        }
    }

    @Test
    void cleanupFailuresRemainSecondaryAndDoNotReterminalizeOperation() {
        for (Failure failure : List.of(Failure.CLEANUP_SCHEDULER, Failure.RESTORE, Failure.BARRIER_RESTORE)) {
            Harness harness = new Harness(Failure.BLOCK_ENTITY_PACKET, failure);
            harness.send();

            assertEquals(1, harness.callbacks.get(), failure.toString());
            assertEquals(ProbeResponse.Outcome.ERROR, harness.outcome.get(), failure.toString());
            assertEquals(0, harness.transport.activeOperations(), failure.toString());
            assertEquals(failure == Failure.CLEANUP_SCHEDULER ? 0 : 1,
                    harness.world.restoreAttempts.get(), failure.toString());
        }
    }

    @Test
    void shutdownBeforeAndAfterEditorOpeningStopsStagesAndRestoresOnce() {
        Harness beforeEditor = new Harness(Failure.NONE, Failure.NONE);
        beforeEditor.send();
        beforeEditor.transport.stop();
        beforeEditor.runPendingGlobal();
        assertEquals(ProbeResponse.Outcome.DISCONNECTED, beforeEditor.outcome.get());
        assertEquals(1, beforeEditor.callbacks.get());
        assertEquals(0, beforeEditor.packets.openCount.get());
        assertEquals(0, beforeEditor.transport.activeOperations());
        assertEquals(1, beforeEditor.world.restores.get());

        Harness editorOpen = new Harness(Failure.NONE, Failure.NONE);
        editorOpen.send();
        editorOpen.scheduler.runNextGlobal();
        assertEquals(1, editorOpen.packets.openCount.get());
        editorOpen.transport.stop();
        editorOpen.runPendingGlobal();
        assertEquals(ProbeResponse.Outcome.DISCONNECTED, editorOpen.outcome.get());
        assertEquals(1, editorOpen.callbacks.get());
        assertEquals(1, editorOpen.packets.openCount.get(), "timeout cannot advance or reopen a terminal operation");
        assertEquals(0, editorOpen.transport.activeOperations());
        assertEquals(1, editorOpen.world.restores.get());
    }

    @Test
    void productionTimeoutTaskUsesControlledTicksForBeforeDeadlineAtDeadlineAndAfterDeadlineRaces()
            throws Exception {
        Harness before = new Harness(Failure.NONE, Failure.NONE);
        before.send();
        before.scheduler.runNextGlobal(); // open editor at tick 1; timeout remains due at tick 20
        before.transport.acceptResponse(response(before, ProbeResponse.Outcome.RESPONSE));
        before.scheduler.runPendingGlobal();
        assertEquals(ProbeResponse.Outcome.RESPONSE, before.outcome.get());
        assertTerminalCleanupOnce(before);

        Harness atBoundary = new Harness(Failure.NONE, Failure.NONE);
        atBoundary.send();
        atBoundary.scheduler.runNextGlobal();
        Runnable timeout = atBoundary.scheduler.takeNextGlobal();
        race(timeout, () -> atBoundary.transport.acceptResponse(response(atBoundary,
                ProbeResponse.Outcome.RESPONSE)));
        assertTrue(atBoundary.outcome.get() == ProbeResponse.Outcome.RESPONSE
                || atBoundary.outcome.get() == ProbeResponse.Outcome.TIMEOUT);
        assertTerminalCleanupOnce(atBoundary);

        Harness after = new Harness(Failure.NONE, Failure.NONE);
        after.send();
        after.scheduler.runNextGlobal();
        after.scheduler.runNextGlobal(); // execute actual scheduled timeout at tick 20
        after.transport.acceptResponse(response(after, ProbeResponse.Outcome.RESPONSE));
        assertEquals(ProbeResponse.Outcome.TIMEOUT, after.outcome.get());
        assertTerminalCleanupOnce(after);
    }

    @Test
    void productionTimeoutTaskRacesCancelAndShutdownExactlyOnce() throws Exception {
        for (boolean shutdown : List.of(false, true)) {
            Harness harness = new Harness(Failure.NONE, Failure.NONE);
            ProbeHandle handle = harness.send();
            harness.scheduler.runNextGlobal(); // open editor
            Runnable timeout = harness.scheduler.takeNextGlobal();
            race(timeout, () -> {
                if (shutdown) harness.transport.stop();
                else handle.cancel();
            });
            if (shutdown) {
                assertTrue(harness.outcome.get() == ProbeResponse.Outcome.TIMEOUT
                        || harness.outcome.get() == ProbeResponse.Outcome.DISCONNECTED);
                assertEquals(1, harness.callbacks.get());
            } else {
                assertTrue(harness.outcome.get() == null || harness.outcome.get() == ProbeResponse.Outcome.TIMEOUT,
                        "transport cancellation is owned and terminalized by its session owner");
                assertTrue(harness.callbacks.get() == 0 || harness.callbacks.get() == 1);
            }
            assertEquals(1, harness.world.restores.get());
            assertEquals(1, harness.world.barrierRestores.get());
            assertEquals(0, harness.transport.activeOperations());
            assertEquals(1, harness.packets.openCount.get());
        }
    }

    @Test
    void cleanupReusesTheRegionTargetCapturedDuringPlacement() {
        Harness harness = new Harness(Failure.NONE, Failure.NONE);
        harness.send();
        harness.scheduler.runNextGlobal(); // open editor
        harness.scheduler.runPendingGlobal(); // drain the scheduled timeout

        assertEquals(2, harness.scheduler.regionTargets.size(), "placement and cleanup each schedule once");
        assertTrue(harness.scheduler.regionTargets.get(0) == harness.scheduler.regionTargets.get(1),
                "cleanup must reuse the placement region target instead of rebuilding one from a Location");
    }

    @Test
    void cleanupStillRestoresWhenTheWorldCannotBeReDerivedAfterPlacement() {
        Harness harness = new Harness(Failure.NONE, Failure.NONE);
        // Models Paper's weak-referenced Location world: only the target captured while the
        // region context was active is usable. Rebuilding one from the Location is refused.
        Scheduler refusing = new Scheduler() {
            private final java.util.Set<RegionTarget> usable = new java.util.HashSet<>();
            @Override public TaskHandle runAtEntity(EntityTarget t, Runnable task) { task.run(); return handleOf(); }
            @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) {
                if (usable.isEmpty()) {
                    usable.add(target);
                } else if (!usable.contains(target)) {
                    throw new IllegalStateException("world reference no longer resolvable");
                }
                task.run();
                return handleOf();
            }
            @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handleOf(); }
            @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
                return harness.scheduler.runGlobalLater(task, delayTicks);
            }
            @Override public TaskHandle runAsync(Runnable task) { task.run(); return handleOf(); }
            @Override public void shutdown() { }
            private TaskHandle handleOf() {
                return new TaskHandle() {
                    @Override public void cancel() { }
                    @Override public boolean cancelled() { return false; }
                };
            }
        };
        PaperSignProbeTransport transport = new PaperSignProbeTransport(plugin(), refusing, 20, false,
                harness.world, harness.packets);
        transport.send(harness.request, response -> {
            harness.callbacks.incrementAndGet();
            harness.outcome.set(response.outcome());
        });
        harness.scheduler.runNextGlobal(); // open editor
        harness.scheduler.runPendingGlobal(); // drain the scheduled timeout

        assertEquals(1, harness.world.restores.get(), "sign restored despite an unusable world reference");
        assertEquals(1, harness.world.barrierRestores.get(), "barrier restored despite an unusable world reference");
        assertEquals(1, harness.callbacks.get());
        assertEquals(0, transport.activeOperations());
    }

    @Test
    void cleanupCallbackFailureKeepsThePrimaryResultAndLeaksNoOperation() {
        Harness harness = new Harness(Failure.NONE, Failure.RESTORE);
        harness.send();
        harness.scheduler.runNextGlobal();
        harness.scheduler.runPendingGlobal();

        assertEquals(1, harness.callbacks.get(), "primary result is reported exactly once");
        assertEquals(ProbeResponse.Outcome.TIMEOUT, harness.outcome.get(),
                "a failed cleanup must not rewrite the primary transport outcome");
        assertEquals(0, harness.transport.activeOperations(), "a failed cleanup must not leak the operation");
        assertEquals(1, harness.world.restoreAttempts.get(), "the restore attempt is still made exactly once");
        assertEquals(2, harness.scheduler.locationCalls, "placement and cleanup each schedule once");
        ReliabilityInvariants.CleanupOutcome outcome = harness.cleanupOutcome();
        ReliabilityInvariants.assertCleanupTerminal("cleanup callback failure", harness.transport, outcome);
        ReliabilityInvariants.assertCleanupFailed("cleanup callback failure", outcome);
    }

    @Test
    void cleanupSchedulingRefusalKeepsThePrimaryResultAndLeaksNoOperation() {
        Harness harness = new Harness(Failure.NONE, Failure.CLEANUP_SCHEDULER);
        harness.send();
        harness.scheduler.runNextGlobal();
        harness.scheduler.runPendingGlobal();

        assertEquals(1, harness.callbacks.get());
        assertEquals(ProbeResponse.Outcome.TIMEOUT, harness.outcome.get(),
                "an unschedulable cleanup must not rewrite the primary transport outcome");
        assertEquals(0, harness.transport.activeOperations());
        assertEquals(0, harness.world.restores.get(), "nothing is restored when cleanup cannot be scheduled");
    }

    private static ProbeResponse response(Harness harness, ProbeResponse.Outcome outcome) {
        return new ProbeResponse(harness.request.sessionId(), harness.playerId,
                List.of("", "", "", "not-a-keybind"), outcome);
    }

    private static void assertTerminalCleanupOnce(Harness harness) {
        assertEquals(1, harness.callbacks.get());
        assertEquals(1, harness.world.restores.get());
        assertEquals(1, harness.world.barrierRestores.get());
        assertEquals(0, harness.transport.activeOperations());
        assertEquals(1, harness.packets.openCount.get());
    }

    private static void race(Runnable timeout, Runnable competingTerminal) throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(3);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var timeoutFuture = executor.submit(() -> {
                barrier.await();
                timeout.run();
                return null;
            });
            var otherFuture = executor.submit(() -> {
                barrier.await();
                competingTerminal.run();
                return null;
            });
            barrier.await();
            timeoutFuture.get();
            otherFuture.get();
        }
    }

    private enum Failure {
        NONE, CANDIDATE_LOOKUP, AIR_CHECK, CAPTURE, BARRIER, SIGN_PLACE, SIGN_UPDATE,
        PACKET_CREATE, BLOCK_ENTITY_PACKET, OPEN_EDITOR_PACKET, BLOCK_CHANGE_PACKET,
        CLEANUP_SCHEDULER, RESTORE, BARRIER_RESTORE
    }

    private static final class Harness {
        private final UUID playerId = UUID.randomUUID();
        private final FakeScheduler scheduler;
        private final FakeWorld world;
        private final FakePackets packets;
        private final PaperSignProbeTransport transport;
        private final AtomicInteger callbacks = new AtomicInteger();
        private final AtomicReference<ProbeResponse.Outcome> outcome = new AtomicReference<>();
        private final Player player;
        private final ProbeRequest request;

        private Harness(Failure operationFailure, Failure cleanupFailure) {
            scheduler = new FakeScheduler(cleanupFailure);
            World nativeWorld = proxy(World.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
            Location feet = new Location(nativeWorld, 0, 64, 0);
            player = proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "test";
                case "isOnline" -> true;
                case "getLocation" -> feet;
                case "toString" -> "test";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
            world = new FakeWorld(feet, operationFailure, cleanupFailure);
            packets = new FakePackets(operationFailure);
            transport = new PaperSignProbeTransport(plugin(), scheduler, 20, false, world, packets);
            request = new ProbeRequest(UUID.randomUUID(), new DetectionTarget(playerId, "test", true, player),
                    List.of(new ProbeDefinition("probe", "Probe", "key.probe", ProbeMode.TRANSLATE,
                            "fallback", true, ProbeVerificationStatus.UNVERIFIED)));
        }

        private ProbeHandle send() {
            return transport.send(request, response -> {
                callbacks.incrementAndGet();
                outcome.set(response.outcome());
            });
        }

        private void runPendingGlobal() { scheduler.runPendingGlobal(); }

        private ReliabilityInvariants.CleanupOutcome cleanupOutcome() {
            return new ReliabilityInvariants.CleanupOutcome(world.restoreAttempts.get(), world.restores.get(),
                    world.barrierRestores.get(), scheduler.locationCalls - 1, callbacks.get());
        }
    }

    private static final class FakeWorld implements SignProbeWorldAccess {
        private final Location feet;
        private final Failure operationFailure;
        private final Failure cleanupFailure;
        private final AtomicInteger airChecks = new AtomicInteger();
        private final AtomicInteger restores = new AtomicInteger();
        private final AtomicInteger restoreAttempts = new AtomicInteger();
        private final AtomicInteger barrierRestores = new AtomicInteger();
        private final AtomicInteger unsafeAirRestores = new AtomicInteger();

        private FakeWorld(Location feet, Failure operationFailure, Failure cleanupFailure) {
            this.feet = feet;
            this.operationFailure = operationFailure;
            this.cleanupFailure = cleanupFailure;
        }
        @Override public Location playerBlockLocation(Player player) {
            failIf(Failure.CANDIDATE_LOOKUP);
            return feet;
        }
        @Override public boolean isAir(Location location) {
            int count = airChecks.incrementAndGet();
            if (operationFailure == Failure.AIR_CHECK && count == 1) throw new IllegalStateException("air lookup");
            return true;
        }
        @Override public BlockState capture(Location location) {
            failIf(Failure.CAPTURE);
            return proxy(BlockState.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
        }
        @Override public void setType(Location location, org.bukkit.Material material) {
            if (material == org.bukkit.Material.BARRIER) failIf(Failure.BARRIER);
            if (material == org.bukkit.Material.OAK_SIGN) failIf(Failure.SIGN_PLACE);
            if (material == org.bukkit.Material.AIR) {
                if (cleanupFailure == Failure.BARRIER_RESTORE) throw new IllegalStateException("barrier restore");
                barrierRestores.incrementAndGet();
                unsafeAirRestores.incrementAndGet();
            }
        }
        @Override public Sign configureSign(Location location, List<ProbeDefinition> probes, UUID editor) {
            if (operationFailure == Failure.SIGN_UPDATE) throw new IllegalStateException("sign update");
            return proxy(Sign.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
        }
        @Override public boolean restore(BlockState original) {
            restoreAttempts.incrementAndGet();
            if (cleanupFailure == Failure.RESTORE) throw new IllegalStateException("restore");
            restores.incrementAndGet();
            return true;
        }
        private void failIf(Failure failure) {
            if (operationFailure == failure) throw new IllegalStateException(failure.name());
        }
    }

    private static final class FakePackets implements SignProbePacketAccess {
        private final Failure failure;
        private final AtomicInteger openCount = new AtomicInteger();
        private FakePackets(Failure failure) { this.failure = failure; }
        @Override public Object createBlockEntityPacket(Location location, Plugin plugin) {
            if (failure == Failure.PACKET_CREATE) throw new IllegalStateException("packet create");
            return new Object();
        }
        @Override public boolean sendBlockEntity(Player player, Object packet, Plugin plugin) {
            if (failure == Failure.BLOCK_ENTITY_PACKET) throw new IllegalStateException("block packet");
            return true;
        }
        @Override public boolean openEditor(Player player, Location location, Plugin plugin) {
            openCount.incrementAndGet();
            if (failure == Failure.OPEN_EDITOR_PACKET) throw new IllegalStateException("open editor");
            return true;
        }
        @Override public void hideTemporarySign(Player player, Location location) {
            if (failure == Failure.BLOCK_CHANGE_PACKET) throw new IllegalStateException("block change");
        }
    }

    private static final class FakeScheduler implements Scheduler {
        private final List<Scheduled> global = new ArrayList<>();
        private final List<RegionTarget> regionTargets = new ArrayList<>();
        private final Failure cleanupFailure;
        private int locationCalls;
        private long ticks;
        private FakeScheduler(Failure cleanupFailure) { this.cleanupFailure = cleanupFailure; }
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) {
            locationCalls++;
            regionTargets.add(target);
            if (locationCalls > 1 && cleanupFailure == Failure.CLEANUP_SCHEDULER) {
                throw new IllegalStateException("cleanup scheduler unavailable");
            }
            task.run();
            return handle();
        }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
            FakeTaskHandle handle = new FakeTaskHandle();
            global.add(new Scheduled(ticks + Math.max(0, delayTicks), task, handle));
            return handle;
        }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }
        private void runPendingGlobal() {
            while (!global.isEmpty()) runNextGlobal();
        }
        private void runNextGlobal() {
            Scheduled next = nextScheduled();
            ticks = Math.max(ticks, next.dueTick());
            if (!next.handle().cancelled()) next.task().run();
        }
        private Runnable takeNextGlobal() {
            Scheduled next = nextScheduled();
            ticks = Math.max(ticks, next.dueTick());
            return next.task(); // Models a timeout already dequeued when a response races it.
        }
        private Scheduled nextScheduled() {
            Scheduled next = global.stream().min(Comparator.comparingLong(Scheduled::dueTick)).orElseThrow();
            global.remove(next);
            return next;
        }
        private static TaskHandle handle() {
            return new FakeTaskHandle();
        }
        private record Scheduled(long dueTick, Runnable task, FakeTaskHandle handle) { }
    }

    private static final class FakeTaskHandle implements TaskHandle {
        private volatile boolean cancelled;
        @Override public void cancel() { cancelled = true; }
        @Override public boolean cancelled() { return cancelled; }
    }

    private static Plugin plugin() {
        Logger logger = Logger.getLogger("transport-test");
        PluginManager manager = proxy(PluginManager.class, (proxy, method, args) -> null);
        Server server = proxy(Server.class, (proxy, method, args) ->
                method.getName().equals("getPluginManager") ? manager : defaultValue(method.getReturnType()));
        return proxy(Plugin.class, (proxy, method, args) -> switch (method.getName()) {
            case "getLogger" -> logger;
            case "getServer" -> server;
            case "getName" -> "test";
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
