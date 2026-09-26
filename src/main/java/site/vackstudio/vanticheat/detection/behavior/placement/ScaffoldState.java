package site.vackstudio.vanticheat.detection.behavior.placement;

import site.vackstudio.vanticheat.detection.behavior.BehaviorObservation;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.PlacementObservation;
import site.vackstudio.vanticheat.detection.behavior.RotationObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.movement.FlyMovementState;

import java.util.EnumSet;
import java.util.List;

public final class ScaffoldState {
    private final int capacity;
    private PlacementObservation previous;
    private int timingStreak;
    private int backwardStreak;
    private int movementStreak;
    private int geometryStreak;
    private int supportStreak;
    private int sequenceLength;
    private SignalLevel lastReported;

    public ScaffoldState(int capacity) {
        if (capacity < 8) throw new IllegalArgumentException("capacity must be at least 8");
        this.capacity = capacity;
    }

    public synchronized ScaffoldAnalysis observe(PlacementObservation current,
                                                  List<BehaviorObservation> rotations,
                                                  List<BehaviorObservation> movements,
                                                  FlyMovementState movementState) {
        if (previous == null) {
            previous = current;
            sequenceLength = 1;
            return null;
        }
        long interval = Math.max(0, (current.timestampNanos() - previous.timestampNanos()) / 1_000_000);
        if (current.timestampNanos() <= previous.timestampNanos() || interval == 0 || interval > 250
                || current.context().timingUncertain() || current.context().uncertain()
                || current.context().specialMovement()) {
            resetFor(current);
            return null;
        }
        sequenceLength = Math.min(capacity, sequenceLength + 1);
        boolean supportAdjacent = adjacent(current.placedPosition(), current.supportPosition());
        boolean supportContinues = same(current.supportPosition(), previous.placedPosition());
        boolean moving = latestMovement(current, movements);
        RotationObservation rotation = latestRotation(current, rotations);
        double facingDot = facingDot(current, previous);
        boolean backward = supportContinues && facingDot < -.35 && rotation != null
                && Math.abs(rotation.yawDelta()) + Math.abs(rotation.pitchDelta()) < 4.0;
        if (interval < 80) timingStreak++; else timingStreak = 0;
        if (backward) backwardStreak++; else backwardStreak = 0;
        if (moving) movementStreak++; else movementStreak = 0;
        if (!supportAdjacent || current.distanceToPlacement() > 5.0 || current.face().equals("UNKNOWN")) geometryStreak++;
        else geometryStreak = 0;
        if (supportContinues) supportStreak++; else supportStreak = 0;

        EnumSet<ScaffoldSignalType> signals = EnumSet.noneOf(ScaffoldSignalType.class);
        if (timingStreak >= 4) signals.add(ScaffoldSignalType.PLACEMENT_TIMING_ANOMALY);
        if (backwardStreak >= 3) signals.add(ScaffoldSignalType.ROTATION_PLACEMENT_ANOMALY);
        if (movementStreak >= 3 && backwardStreak >= 3) signals.add(ScaffoldSignalType.MOVEMENT_PLACEMENT_ANOMALY);
        if (geometryStreak >= 2) signals.add(ScaffoldSignalType.PLACEMENT_GEOMETRY_ANOMALY);
        if (supportStreak >= 4 && backwardStreak >= 3 && timingStreak >= 4) {
            signals.add(ScaffoldSignalType.SUPPORT_SEQUENCE_ANOMALY);
        }
        if (signals.size() >= 2 && timingStreak >= 4) {
            signals.add(ScaffoldSignalType.REPEATED_AUTOMATION_PATTERN);
        }
        SignalLevel confidence = confidence(signals);
        previous = current;
        if (signals.isEmpty() || confidence == lastReported) return null;
        lastReported = confidence;
        return new ScaffoldAnalysis(current, signals, interval, facingDot, supportAdjacent,
                supportContinues, moving, movementState, confidence, sequenceLength);
    }

    private SignalLevel confidence(EnumSet<ScaffoldSignalType> signals) {
        if (signals.isEmpty()) return SignalLevel.CLEAR;
        boolean rotation = signals.contains(ScaffoldSignalType.ROTATION_PLACEMENT_ANOMALY);
        boolean timing = signals.contains(ScaffoldSignalType.PLACEMENT_TIMING_ANOMALY);
        boolean geometry = signals.contains(ScaffoldSignalType.PLACEMENT_GEOMETRY_ANOMALY);
        boolean strong = sequenceLength >= 8 && timing && rotation
                && (signals.contains(ScaffoldSignalType.MOVEMENT_PLACEMENT_ANOMALY) || geometry);
        if (strong) return SignalLevel.STRONG_SIGNAL;
        if ((rotation && (timing || geometry)) && sequenceLength >= 5) return SignalLevel.SUSPICIOUS;
        return SignalLevel.UNCERTAIN;
    }

    private static boolean latestMovement(PlacementObservation current, List<BehaviorObservation> movements) {
        for (int i = movements.size() - 1; i >= 0; i--) {
            if (!(movements.get(i) instanceof MovementObservation movement)) continue;
            long age = current.timestampNanos() - movement.timestampNanos();
            if (age >= 0 && age <= 250_000_000L) {
                return movement.horizontalDistance() > .25 || !movement.grounded();
            }
            if (age > 250_000_000L) break;
        }
        return false;
    }

    private static RotationObservation latestRotation(PlacementObservation current,
                                                      List<BehaviorObservation> rotations) {
        for (int i = rotations.size() - 1; i >= 0; i--) {
            if (!(rotations.get(i) instanceof RotationObservation rotation)) continue;
            long age = current.timestampNanos() - rotation.timestampNanos();
            if (age >= 0 && age <= 250_000_000L) return rotation;
            if (age > 250_000_000L) break;
        }
        return null;
    }

    private static boolean adjacent(site.vackstudio.vanticheat.detection.behavior.Position3d a,
                                    site.vackstudio.vanticheat.detection.behavior.Position3d b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()) == 1.0;
    }

    private static boolean same(site.vackstudio.vanticheat.detection.behavior.Position3d a,
                                site.vackstudio.vanticheat.detection.behavior.Position3d b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }

    private static double facingDot(PlacementObservation current, PlacementObservation prior) {
        double dx = current.placedPosition().x() - prior.placedPosition().x();
        double dz = current.placedPosition().z() - prior.placedPosition().z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) return 1.0;
        double yaw = Math.toRadians(current.yaw());
        double facingX = -Math.sin(yaw);
        double facingZ = Math.cos(yaw);
        return (dx * facingX + dz * facingZ) / length;
    }

    private void resetFor(PlacementObservation current) {
        previous = current;
        timingStreak = 0;
        backwardStreak = 0;
        movementStreak = 0;
        geometryStreak = 0;
        supportStreak = 0;
        sequenceLength = 1;
        lastReported = null;
    }

    public synchronized void reset() {
        previous = null;
        timingStreak = 0;
        backwardStreak = 0;
        movementStreak = 0;
        geometryStreak = 0;
        supportStreak = 0;
        sequenceLength = 0;
        lastReported = null;
    }
}
