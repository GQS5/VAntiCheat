package site.vackstudio.vanticheat.detection.behavior.placement;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorRegistry;
import site.vackstudio.vanticheat.detection.behavior.MovementContext;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.PlacementObservation;
import site.vackstudio.vanticheat.detection.behavior.Position3d;
import site.vackstudio.vanticheat.detection.behavior.RotationObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldDetectorTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    void fastForwardPlacementAloneDoesNotBecomeScaffoldEvidence() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 12; i++) registry.observe(place(player, i * 50L, i, false));
        assertTrue(signals.stream().noneMatch(value -> value.level().ordinal() >= SignalLevel.SUSPICIOUS.ordinal()));
    }

    @Test
    void repeatedBackwardPlacementAggregatesConservatively() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 12; i++) {
            long millis = i * 50L;
            registry.observe(new RotationObservation(player, millis * MILLIS, 90, 20, 1, 1));
            registry.observe(new MovementObservation(player, millis * MILLIS - 10 * MILLIS,
                    new Position3d(i, 64, 0), new Position3d(i + .3, 64, 0), .3, 0, 0,
                    .3, 0, 50, true, false, new Position3d(0, 0, 0), MovementContext.normal()));
            registry.observe(place(player, millis, i, true));
        }
        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.SUSPICIOUS));
        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.STRONG_SIGNAL));
    }

    @Test
    void uncertainPlacementResetsSequence() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 6; i++) registry.observe(place(player, i * 50L, i, true));
        registry.observe(place(player, 300, 6, true, true));
        for (int i = 7; i < 12; i++) registry.observe(place(player, i * 50L, i, true));
        assertTrue(signals.stream().noneMatch(value -> value.level() == SignalLevel.STRONG_SIGNAL));
    }

    private static BehaviorRegistry registry(CopyOnWriteArrayList<ViolationSignal> signals) {
        BehaviorRegistry registry = new BehaviorRegistry(128);
        registry.register(new ScaffoldDetector(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signals::add));
        return registry;
    }

    private static PlacementObservation place(UUID player, long millis, int x, boolean backward) {
        return place(player, millis, x, backward, false);
    }

    private static PlacementObservation place(UUID player, long millis, int x, boolean backward, boolean uncertain) {
        Position3d placed = new Position3d(x, 64, 0);
        Position3d support = new Position3d(x - 1, 64, 0);
        MovementContext context = uncertain
                ? new MovementContext(false, false, false, false, false, false, false, false,
                false, false, true)
                : MovementContext.normal();
        return new PlacementObservation(player, millis * MILLIS, placed, support, "EAST", "STONE", "STONE",
                new Position3d(x, 64, 1), backward ? 90 : 0, 20, true, false, context, 3);
    }
}
