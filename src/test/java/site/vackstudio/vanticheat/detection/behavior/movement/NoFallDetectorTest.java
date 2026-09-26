package site.vackstudio.vanticheat.detection.behavior.movement;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorRegistry;
import site.vackstudio.vanticheat.detection.behavior.FallDamageObservation;
import site.vackstudio.vanticheat.detection.behavior.MovementContext;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.Position3d;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoFallDetectorTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    void expectedFallDamageCorrelatesAndRemainsClear() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(ground(player, 0, 10, normal()));
        registry.observe(air(player, 50, 6, -4, normal()));
        registry.observe(air(player, 100, 5, -1, normal()));
        registry.observe(new FallDamageObservation(player, 120 * MILLIS, 5.0));
        registry.observe(land(player, 150, 4, normal()));
        registry.observe(ground(player, 300, 4, normal()));

        assertTrue(signals.isEmpty());
    }

    @Test
    void repeatedMissingImpactBecomesSuspiciousThenStrong() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            long base = i * 500L;
            registry.observe(ground(player, base, 10, normal()));
            registry.observe(air(player, base + 50, 6, -4, normal()));
            registry.observe(air(player, base + 100, 5, -1, normal()));
            registry.observe(land(player, base + 150, 4, normal()));
            registry.observe(ground(player, base + 300, 4, normal()));
        }

        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.UNCERTAIN));
        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.SUSPICIOUS));
        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.STRONG_SIGNAL));
    }

    @Test
    void safeLandingAndSpecialMovementDoNotCreateMismatch() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(ground(player, 0, 10, normal()));
        registry.observe(air(player, 50, 6, -4, normal()));
        registry.observe(air(player, 100, 5, -1, normal()));
        registry.observe(land(player, 150, 4, safeLanding("HAY_BLOCK")));
        registry.observe(ground(player, 300, 4, safeLanding("HAY_BLOCK")));
        assertFalse(signals.stream().anyMatch(value -> value.detectorId().equals("no-fall")));
    }

    @Test
    void delayedObservationAndResetDiscardIncompleteFall() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(ground(player, 0, 10, normal()));
        registry.observe(air(player, 50, 6, -4, normal()));
        registry.observe(air(player, 100, 5, -1, normal()));
        registry.observe(land(player, 150, 4, normal()));
        registry.remove(player);
        registry.observe(ground(player, 1000, 4, normal()));
        assertTrue(signals.isEmpty());
    }

    private static BehaviorRegistry registry(CopyOnWriteArrayList<ViolationSignal> signals) {
        BehaviorRegistry registry = new BehaviorRegistry(128);
        registry.register(new NoFallDetector(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signals::add));
        return registry;
    }

    private static MovementContext normal() { return MovementContext.normal(); }

    private static MovementContext safeLanding(String surface) {
        return new MovementContext(false, false, false, false, false, false, false,
                false, false, false, false, true, true, surface);
    }

    private static MovementObservation ground(UUID player, long millis, double y, MovementContext context) {
        return observation(player, millis, y, y, 0, true, context);
    }

    private static MovementObservation land(UUID player, long millis, double y, MovementContext context) {
        return observation(player, millis, y + .5, y, -.5, true, context);
    }

    private static MovementObservation air(UUID player, long millis, double y, double deltaY,
                                           MovementContext context) {
        return observation(player, millis, y - deltaY, y, deltaY, false, context);
    }

    private static MovementObservation observation(UUID player, long millis, double fromY, double toY,
                                                   double deltaY, boolean grounded, MovementContext context) {
        return new MovementObservation(player, millis * MILLIS,
                new Position3d(0, fromY, 0), new Position3d(0, toY, 0), 0, deltaY, 0,
                0, Math.abs(deltaY), 50, grounded, false, new Position3d(0, deltaY, 0), context);
    }
}
