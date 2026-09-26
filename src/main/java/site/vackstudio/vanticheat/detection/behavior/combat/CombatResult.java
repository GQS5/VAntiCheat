package site.vackstudio.vanticheat.detection.behavior.combat;

import java.util.List;

public record CombatResult(AttackSequence sequence, CombatConfidence confidence,
                           int repeatedTiming, int repeatedTargetSwitches,
                           int repeatedRotations, int repeatedReach,
                           List<AttackSequence> recentAttacks) {
    public CombatResult {
        recentAttacks = List.copyOf(recentAttacks);
    }
}
