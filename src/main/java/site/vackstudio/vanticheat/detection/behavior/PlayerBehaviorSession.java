package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;
import java.util.UUID;
import site.vackstudio.vanticheat.detection.behavior.combat.CombatState;
import site.vackstudio.vanticheat.detection.behavior.movement.FlyState;
import site.vackstudio.vanticheat.detection.behavior.movement.NoFallState;
import site.vackstudio.vanticheat.detection.behavior.movement.SpeedState;
import site.vackstudio.vanticheat.detection.behavior.placement.ScaffoldState;
import site.vackstudio.vanticheat.detection.behavior.combat.autoclicker.AutoClickerState;

public final class PlayerBehaviorSession {
    private final UUID playerId;
    private final ObservationWindow movement;
    private final ObservationWindow rotation;
    private final ObservationWindow combat;
    private final ObservationWindow interaction;
    private final ObservationWindow impact;
    private final ObservationWindow placement;
    private final CombatState combatState;
    private final FlyState flyState;
    private final NoFallState noFallState;
    private final SpeedState speedState;
    private final ScaffoldState scaffoldState;
    private final AutoClickerState autoClickerState;

    public PlayerBehaviorSession(UUID playerId, int windowCapacity) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        movement = new ObservationWindow(windowCapacity);
        rotation = new ObservationWindow(windowCapacity);
        combat = new ObservationWindow(windowCapacity);
        interaction = new ObservationWindow(windowCapacity);
        impact = new ObservationWindow(Math.min(windowCapacity, 32));
        placement = new ObservationWindow(Math.min(windowCapacity, 64));
        combatState = new CombatState(Math.max(4, Math.min(windowCapacity, 32)));
        flyState = new FlyState(Math.max(8, Math.min(windowCapacity, 64)));
        noFallState = new NoFallState(Math.max(4, Math.min(windowCapacity, 32)));
        speedState = new SpeedState();
        scaffoldState = new ScaffoldState(Math.max(8, Math.min(windowCapacity, 32)));
        autoClickerState = new AutoClickerState();
    }

    public void record(BehaviorObservation observation) {
        Objects.requireNonNull(observation, "observation");
        switch (observation.type()) {
            case MOVEMENT -> movement.add(observation);
            case ROTATION -> rotation.add(observation);
            case COMBAT -> combat.add(observation);
            case INTERACTION -> interaction.add(observation);
            case IMPACT -> impact.add(observation);
            case PLACEMENT -> placement.add(observation);
        }
    }

    public UUID playerId() { return playerId; }
    public ObservationWindow movement() { return movement; }
    public ObservationWindow rotation() { return rotation; }
    public ObservationWindow combat() { return combat; }
    public ObservationWindow interaction() { return interaction; }
    public ObservationWindow impact() { return impact; }
    public ObservationWindow placement() { return placement; }
    public CombatState combatState() { return combatState; }
    public FlyState flyState() { return flyState; }
    public NoFallState noFallState() { return noFallState; }
    public SpeedState speedState() { return speedState; }
    public ScaffoldState scaffoldState() { return scaffoldState; }
    public AutoClickerState autoClickerState() { return autoClickerState; }
}
