package site.vackstudio.vanticheat.detection.behavior.movement;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorRegistry;
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

class FlyDetectorTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    void normalJumpAndLandingRemainClear() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(movement(player, 0, 0, 0, 0, true, MovementContext.normal()));
        registry.observe(movement(player, 50, .4, .4, .2, false, MovementContext.normal()));
        registry.observe(movement(player, 100, .3, .3, .15, false, MovementContext.normal()));
        registry.observe(movement(player, 150, .2, .2, .1, false, MovementContext.normal()));
        registry.observe(movement(player, 200, -.3, -.3, .1, false, MovementContext.normal()));
        registry.observe(movement(player, 250, -.4, -.4, .1, true, MovementContext.normal()));

        assertTrue(signals.isEmpty());
    }

    @Test
    void hoverBecomesSuspiciousOnlyAfterPersistence() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(movement(player, 0, 0, 0, 0, true, MovementContext.normal()));
        registry.observe(movement(player, 50, .1, .1, 1, false, MovementContext.normal()));
        for (int i = 2; i <= 18; i++) {
            registry.observe(movement(player, i * 50L, 0, 0, 0, false, MovementContext.normal()));
        }

        assertTrue(signals.stream().anyMatch(signal -> signal.level() == SignalLevel.UNCERTAIN));
        assertTrue(signals.stream().anyMatch(signal -> signal.level() == SignalLevel.SUSPICIOUS));
    }

    @Test
    void repeatedIndependentAerialAnomaliesBecomeStrong() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(movement(player, 0, 0, 0, 0, true, MovementContext.normal()));
        double x = 0;
        for (int i = 1; i <= 65; i++) {
            double deltaY = i % 2 == 0 ? .1 : -.1;
            x += .6;
            registry.observe(new MovementObservation(player, i * 50L * MILLIS,
                    new Position3d(x - .6, 65, 0), new Position3d(x, 65 + deltaY, 0),
                    .6, deltaY, 0, .6, Math.abs(deltaY), 50, false, false,
                    new Position3d(0, deltaY * 2, 0), MovementContext.normal()));
        }

        assertTrue(signals.stream().anyMatch(signal -> signal.level() == SignalLevel.STRONG_SIGNAL));
    }

    @Test
    void specialMovementAndTimingGapsDoNotBecomeFlyEvidence() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        MovementContext elytra = new MovementContext(false, false, false, false, false, true,
                false, false, false, false, false);
        registry.observe(movement(player, 0, 0, 0, 0, false, elytra));
        registry.observe(movement(player, 50, 2, 2, 20, false, elytra));
        registry.observe(movement(player, 100, 2, 2, 20, false, MovementContext.normal()));
        registry.observe(movement(player, 700, 2, 2, 20, false, MovementContext.normal()));

        assertFalse(signals.stream().anyMatch(signal -> signal.detectorId().equals("fly")));
    }

    private static BehaviorRegistry registry(CopyOnWriteArrayList<ViolationSignal> signals) {
        BehaviorRegistry registry = new BehaviorRegistry(128);
        registry.register(new FlyDetector(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signals::add));
        return registry;
    }

    private static MovementObservation movement(UUID player, long millis, double deltaY,
                                                double velocityY, double horizontal, boolean grounded,
                                                MovementContext context) {
        return new MovementObservation(player, millis * MILLIS,
                new Position3d(0, 64, 0), new Position3d(horizontal, 64 + deltaY, 0),
                horizontal, deltaY, 0, horizontal, Math.abs(deltaY), 50, grounded,
                false, new Position3d(0, velocityY, 0), context);
    }
}
