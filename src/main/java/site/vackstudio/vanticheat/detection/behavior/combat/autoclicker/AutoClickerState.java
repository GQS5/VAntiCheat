package site.vackstudio.vanticheat.detection.behavior.combat.autoclicker;

import site.vackstudio.vanticheat.detection.behavior.BehaviorObservation;
import site.vackstudio.vanticheat.detection.behavior.CombatObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class AutoClickerState {
    private static final int ANALYSIS_WINDOW = 24;
    private static final long MAX_INTERVAL_MILLIS = 2_000;
    private int persistentPattern;
    private SignalLevel lastReported;

    public synchronized AutoClickerAnalysis observe(CombatObservation current,
                                                     List<BehaviorObservation> combatWindow) {
        List<CombatObservation> attacks = attacks(combatWindow);
        if (attacks.isEmpty() || attacks.get(attacks.size() - 1) != current) return null;
        int from = Math.max(0, attacks.size() - ANALYSIS_WINDOW);
        List<CombatObservation> samples = attacks.subList(from, attacks.size());
        List<Long> intervals = new ArrayList<>();
        boolean uncertain = false;
        boolean gap = false;
        for (int i = 1; i < samples.size(); i++) {
            CombatObservation prior = samples.get(i - 1);
            CombatObservation attack = samples.get(i);
            long interval = attack.attackIntervalMillis() > 0
                    ? attack.attackIntervalMillis()
                    : Math.max(0, (attack.timestampNanos() - prior.timestampNanos()) / 1_000_000);
            if (interval == 0) uncertain = true;
            if (interval > MAX_INTERVAL_MILLIS) gap = true;
            if (interval > 0 && interval <= MAX_INTERVAL_MILLIS) intervals.add(interval);
            uncertain |= attack.uncertain() || attack.attackStrength() < .15f;
        }
        if (intervals.size() < 3) {
            resetPattern();
            return null;
        }
        TimingQuality quality = quality(intervals, uncertain, gap);
        double mean = mean(intervals);
        double variance = variance(intervals, mean);
        int repeated = repeated(intervals);
        double periodicity = periodicity(intervals);
        int burstLength = burstLength(intervals);
        double cadence = mean == 0 ? 0 : 1_000.0 / mean;
        EnumSet<AutoClickerSignalType> signals = EnumSet.noneOf(AutoClickerSignalType.class);
        boolean regular = periodicity >= .75 && intervals.size() >= 5;
        boolean lowVariance = mean > 0 && Math.sqrt(variance) / mean <= .035 && intervals.size() >= 5;
        boolean periodic = repeated >= 5 && intervals.size() >= 6;
        if (regular) signals.add(AutoClickerSignalType.TIMING_REGULARITY);
        if (lowVariance) signals.add(AutoClickerSignalType.INTERVAL_VARIANCE);
        if (periodic) signals.add(AutoClickerSignalType.PERIODIC_PATTERN);
        if (burstLength >= 6) signals.add(AutoClickerSignalType.BURST_PATTERN);
        if (intervals.size() >= 6 && mean <= 300) signals.add(AutoClickerSignalType.ATTACK_CADENCE);
        if (tickQuantized(intervals)) signals.add(AutoClickerSignalType.TICK_QUANTIZATION);
        if (quality != TimingQuality.RELIABLE) signals.add(AutoClickerSignalType.TIMING_UNCERTAINTY);
        if (cooldownContext(samples)) signals.add(AutoClickerSignalType.COOLDOWN_CONTEXT);
        if (targetSwitches(samples) > 0) signals.add(AutoClickerSignalType.TARGET_CONTEXT);

        boolean pattern = regular || lowVariance || periodic;
        if (pattern && quality == TimingQuality.RELIABLE) persistentPattern++;
        else if (!pattern || quality != TimingQuality.RELIABLE) persistentPattern = 0;
        SignalLevel confidence = confidence(signals, quality, intervals.size(), persistentPattern);
        if (signals.isEmpty() || confidence == lastReported) return null;
        lastReported = confidence;
        return new AutoClickerAnalysis(current, intervals.size(), mean, variance, repeated, burstLength,
                periodicity, cadence, quality, signals, confidence);
    }

    private SignalLevel confidence(EnumSet<AutoClickerSignalType> signals, TimingQuality quality,
                                   int samples, int persistence) {
        if (signals.isEmpty()) return SignalLevel.CLEAR;
        if (quality == TimingQuality.DISTORTED || quality == TimingQuality.UNKNOWN) return SignalLevel.UNCERTAIN;
        boolean pattern = signals.contains(AutoClickerSignalType.TIMING_REGULARITY)
                || signals.contains(AutoClickerSignalType.INTERVAL_VARIANCE)
                || signals.contains(AutoClickerSignalType.PERIODIC_PATTERN);
        boolean strong = quality == TimingQuality.RELIABLE && samples >= 10 && persistence >= 2
                && pattern && signals.contains(AutoClickerSignalType.ATTACK_CADENCE)
                && (signals.contains(AutoClickerSignalType.PERIODIC_PATTERN)
                || signals.contains(AutoClickerSignalType.BURST_PATTERN));
        if (strong) return SignalLevel.STRONG_SIGNAL;
        if (quality == TimingQuality.RELIABLE && samples >= 6 && pattern && persistence >= 1) {
            return SignalLevel.SUSPICIOUS;
        }
        return SignalLevel.UNCERTAIN;
    }

    private static TimingQuality quality(List<Long> intervals, boolean uncertain, boolean gap) {
        if (intervals.isEmpty()) return TimingQuality.UNKNOWN;
        if (uncertain) return TimingQuality.DISTORTED;
        if (gap || intervals.stream().anyMatch(value -> value < 20) || tickQuantized(intervals)) {
            return TimingQuality.PARTIAL;
        }
        return TimingQuality.RELIABLE;
    }

    private static double mean(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static double variance(List<Long> values, double mean) {
        return values.stream().mapToDouble(value -> (value - mean) * (value - mean)).average().orElse(0);
    }

    private static int repeated(List<Long> intervals) {
        int repeated = 0;
        for (int i = 1; i < intervals.size(); i++) {
            if (Math.abs(intervals.get(i) - intervals.get(i - 1)) <= 2) repeated++;
        }
        return repeated;
    }

    private static double periodicity(List<Long> intervals) {
        if (intervals.size() < 2) return 0;
        int matches = 0;
        for (int i = 1; i < intervals.size(); i++) {
            if (Math.abs(intervals.get(i) - intervals.get(i - 1)) <= 3) matches++;
        }
        return (double) matches / (intervals.size() - 1);
    }

    private static int burstLength(List<Long> intervals) {
        int current = 1;
        int longest = 1;
        for (long interval : intervals) {
            if (interval <= 350) current++; else current = 1;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    private static boolean tickQuantized(List<Long> intervals) {
        long quantized = intervals.stream().filter(value -> value % 50 == 0).count();
        return intervals.size() >= 5 && quantized * 100 >= intervals.size() * 70;
    }

    private static boolean cooldownContext(List<CombatObservation> attacks) {
        long fullStrength = attacks.stream().filter(value -> value.attackStrength() >= .95f).count();
        return fullStrength > 0 && fullStrength < attacks.size();
    }

    private static int targetSwitches(List<CombatObservation> attacks) {
        int switches = 0;
        for (int i = 1; i < attacks.size(); i++) {
            if (!attacks.get(i).targetId().equals(attacks.get(i - 1).targetId())) switches++;
        }
        return switches;
    }

    private static List<CombatObservation> attacks(List<BehaviorObservation> observations) {
        List<CombatObservation> attacks = new ArrayList<>();
        for (BehaviorObservation observation : observations) {
            if (observation instanceof CombatObservation combat) attacks.add(combat);
        }
        return attacks;
    }

    private void resetPattern() {
        persistentPattern = 0;
        lastReported = null;
    }

    public synchronized void reset() { resetPattern(); }
}
