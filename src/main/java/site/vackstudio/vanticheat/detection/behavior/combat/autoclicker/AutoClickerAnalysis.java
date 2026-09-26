package site.vackstudio.vanticheat.detection.behavior.combat.autoclicker;

import site.vackstudio.vanticheat.detection.behavior.CombatObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.Set;

public record AutoClickerAnalysis(CombatObservation observation, int sampleCount,
                                  double meanIntervalMillis, double intervalVarianceMillisSquared,
                                  int repeatedIntervalCount, int burstLength, double periodicity,
                                  double attackCadence, TimingQuality timingQuality,
                                  Set<AutoClickerSignalType> signalTypes, SignalLevel confidence) {
    public AutoClickerAnalysis {
        signalTypes = Set.copyOf(signalTypes);
    }
}
