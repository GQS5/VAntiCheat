package site.vackstudio.vanticheat.detection.behavior.combat.autoclicker;

import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record AutoClickerEvidence(String detector, long timestampNanos, UUID playerId,
                                  int sampleCount, double meanIntervalMillis,
                                  double intervalVarianceMillisSquared, int repeatedIntervalCount,
                                  int burstLength, double periodicity, double attackCadence,
                                  TimingQuality timingQuality, Set<AutoClickerSignalType> signalTypes,
                                  SignalLevel confidence, Map<String, String> context) {
    public AutoClickerEvidence {
        signalTypes = Set.copyOf(signalTypes);
        context = Map.copyOf(context);
    }
}
