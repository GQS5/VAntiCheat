package site.vackstudio.vanticheat.detection.behavior.combat;

import site.vackstudio.vanticheat.detection.behavior.BehaviorObservation;
import site.vackstudio.vanticheat.detection.behavior.CombatObservation;
import site.vackstudio.vanticheat.detection.behavior.RotationObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CombatState {
    private final int capacity;
    private final Deque<AttackSequence> attacks = new ArrayDeque<>();
    private final Deque<SignalLevel> reachSignals = new ArrayDeque<>();
    private long sequence;

    public CombatState(int capacity) {
        if (capacity < 4) throw new IllegalArgumentException("capacity must be at least 4");
        this.capacity = capacity;
    }

    public synchronized void recordReach(SignalLevel level) {
        reachSignals.addLast(level);
        trim(reachSignals);
    }

    public synchronized CombatResult observe(CombatObservation observation,
                                              List<BehaviorObservation> rotations) {
        AttackSequence prior = attacks.peekLast();
        long interval = observation.attackIntervalMillis();
        if (interval == 0 && prior != null) {
            interval = Math.max(0, (observation.timestampNanos() - prior.timestampNanos()) / 1_000_000);
        }
        RotationObservation rotation = latestRotation(observation, rotations);
        double rotationDelta = rotation == null ? 0.0
                : Math.abs(rotation.yawDelta()) + Math.abs(rotation.pitchDelta());
        EnumSet<CombatSignalType> signals = EnumSet.noneOf(CombatSignalType.class);
        if (interval > 0 && interval <= 125 && observation.attackStrength() >= .85f) {
            signals.add(CombatSignalType.TIMING);
        }
        if (prior != null && !prior.targetId().equals(observation.targetId()) && interval <= 350) {
            signals.add(CombatSignalType.TARGET_SWITCH);
        }
        if (rotationDelta >= 12.0 && rotation != null) signals.add(CombatSignalType.ROTATION);
        SignalLevel recentReach = reachSignals.peekLast();
        if (recentReach == SignalLevel.SUSPICIOUS || recentReach == SignalLevel.STRONG_SIGNAL) {
            signals.add(CombatSignalType.REACH);
            signals.add(CombatSignalType.GEOMETRY);
        }
        long index = ++sequence;
        AttackSequence current = new AttackSequence(index, observation.timestampNanos(), observation.targetId(),
                interval, rotationDelta, signals);
        attacks.addLast(current);
        trim(attacks);

        int repeatedTiming = count(CombatSignalType.TIMING, 6);
        int repeatedSwitches = count(CombatSignalType.TARGET_SWITCH, 6);
        int repeatedRotations = count(CombatSignalType.ROTATION, 6);
        int repeatedReach = count(CombatSignalType.REACH, 6);
        int independent = independentSignals(current, repeatedTiming, repeatedSwitches, repeatedRotations, repeatedReach);
        boolean uncertain = observation.uncertain() || !observation.lineOfSight()
                || observation.attackerMoving() || observation.targetMoving()
                || observation.knockbackContext() || observation.nearbyTargets();
        CombatConfidence confidence = confidence(independent, current, repeatedTiming, repeatedSwitches,
                repeatedRotations, repeatedReach, attacks.size(), uncertain);
        return new CombatResult(current, confidence, repeatedTiming, repeatedSwitches,
                repeatedRotations, repeatedReach, List.copyOf(attacks));
    }

    private CombatConfidence confidence(int independent, AttackSequence current, int timing, int switches,
                                        int rotations, int reach, int size, boolean uncertain) {
        if (independent == 0) return uncertain ? CombatConfidence.UNCERTAIN : CombatConfidence.CLEAR;
        if (uncertain) return CombatConfidence.UNCERTAIN;
        boolean repeated = timing >= 3 || switches >= 3 || rotations >= 3 || reach >= 2;
        if (independent >= 3 && repeated && size >= 5) return CombatConfidence.STRONG_SIGNAL;
        if (independent >= 2 && repeated) return CombatConfidence.SUSPICIOUS;
        return CombatConfidence.UNCERTAIN;
    }

    private int independentSignals(AttackSequence current, int timing, int switches,
                                   int rotations, int reach) {
        int count = 0;
        if (current.signals().contains(CombatSignalType.TIMING) && timing > 0) count++;
        if (current.signals().contains(CombatSignalType.TARGET_SWITCH) && switches > 0) count++;
        if (current.signals().contains(CombatSignalType.ROTATION) && rotations > 0) count++;
        if (current.signals().contains(CombatSignalType.REACH) && reach > 0) count++;
        return count;
    }

    private int count(CombatSignalType signal, int recent) {
        int count = 0;
        int skipped = 0;
        for (AttackSequence attack : reverse(attacks)) {
            if (skipped++ >= recent) break;
            if (attack.signals().contains(signal)) count++;
        }
        return count;
    }

    private RotationObservation latestRotation(CombatObservation attack, List<BehaviorObservation> rotations) {
        for (int i = rotations.size() - 1; i >= 0; i--) {
            BehaviorObservation observation = rotations.get(i);
            if (!(observation instanceof RotationObservation rotation)) continue;
            long age = attack.timestampNanos() - rotation.timestampNanos();
            if (age >= 0 && age <= 300_000_000L) return rotation;
            if (age > 300_000_000L) break;
        }
        return null;
    }

    private static <T> List<T> reverse(Deque<T> values) {
        return new ArrayList<>(values.reversed());
    }

    private void trim(Deque<?> values) {
        while (values.size() > capacity) values.removeFirst();
    }

    public synchronized int attackCount() { return attacks.size(); }
    public synchronized void clear() { attacks.clear(); reachSignals.clear(); sequence = 0; }
}
