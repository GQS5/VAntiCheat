package site.vackstudio.vanticheat.detection.behavior;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorRegistryTest {
    @Test
    void keepsPlayerWindowsBoundedAndCleansUp() {
        BehaviorRegistry registry = new BehaviorRegistry(2);
        registry.register(new ReachBehaviorModule(true));
        AtomicReference<PlayerBehaviorSession> session = new AtomicReference<>();
        registry.register(new BehaviorModule() {
            @Override public BehaviorDefinition definition() { return new BehaviorDefinition("test", "test", true); }
            @Override public void observe(BehaviorObservation observation, PlayerBehaviorSession value,
                                          BehaviorModuleContext context) { session.set(value); }
        });
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signal -> { }));
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 3; i++) registry.observe(movement(player, i));

        assertEquals(2, session.get().movement().size());
        assertEquals(1, registry.sessionCount());
        registry.remove(player);
        assertEquals(0, registry.sessionCount());
        registry.stop();
        assertFalse(registry.started());
    }

    @Test
    void emitsClearAndStrongReachSignalsWithoutEnforcement() {
        AtomicReference<ViolationSignal> last = new AtomicReference<>();
        BehaviorRegistry registry = new BehaviorRegistry();
        registry.register(new ReachBehaviorModule(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), last::set));
        UUID player = UUID.randomUUID();
        registry.observe(combat(player, 3.6));
        assertEquals(SignalLevel.STRONG_SIGNAL, last.get().level());
        registry.observe(combat(player, 2.0));
        assertEquals(SignalLevel.CLEAR, last.get().level());
    }

    private static MovementObservation movement(UUID player, long index) {
        Position3d from = new Position3d(index, 64, 0);
        Position3d to = new Position3d(index + 0.1, 64, 0);
        return new MovementObservation(player, index, from, to, .1, 0, 0, .1, 0, 50, true, false,
                new Position3d(0, 0, 0));
    }

    private static CombatObservation combat(UUID player, double distance) {
        Position3d eye = new Position3d(0, 65.62, 0);
        BoundingBox3d bounds = new BoundingBox3d(distance, 64, -.3, distance + .6, 65.8, .3);
        return new CombatObservation(player, 1, UUID.randomUUID(), new Position3d(0, 64, 0), eye,
                new Position3d(distance, 64, 0), bounds, distance, 100, true, false);
    }
}
