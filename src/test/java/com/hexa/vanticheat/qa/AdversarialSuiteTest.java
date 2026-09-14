package com.hexa.vanticheat.qa;

import com.hexa.vanticheat.checks.CheckResult;
import com.hexa.vanticheat.checks.combat.CombatChecks;
import com.hexa.vanticheat.checks.movement.MovementChecks;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.state.NormalizedCombatState;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial suite: sustained cheating MUST eventually fail a check.
 * Covers: fly-rise, speed, repeated reach, machine click timing, aura rate.
 */
public class AdversarialSuiteTest {

    private final VConfig cfg = new VConfig(null, null);

    @Test
    public void sustainedFlyRiseDetected() {
        MovementChecks move = new MovementChecks(cfg);
        BotHarness.Bot b = new BotHarness.Bot();
        b.onGround = false;
        boolean flagged = false;
        for (int i = 0; i < 30 && !flagged; i++) {
            Location from = b.loc.clone();
            b.loc.add(0, 0.3, 0); // pure vertical rise, no horizontal
            flagged = move.evaluate(b.player, from, b.loc.clone()).failed();
        }
        assertTrue(flagged, "sustained vertical fly must flag");
    }

    @Test
    public void sustainedSpeedDetected() {
        MovementChecks move = new MovementChecks(cfg);
        BotHarness.Bot b = new BotHarness.Bot();
        b.sprinting = false;
        boolean flagged = false;
        for (int i = 0; i < 40 && !flagged; i++) {
            Location from = b.loc.clone();
            b.loc.add(1.2, 0, 0); // 1.2 m/event walk — impossible
            flagged = move.evaluate(b.player, from, b.loc.clone()).failed();
        }
        assertTrue(flagged, "sustained impossible speed must flag");
    }

    @Test
    public void repeatedImpossibleReachDetected() {
        CombatChecks combat = new CombatChecks(cfg);
        UUID id = UUID.randomUUID();
        boolean flagged = false;
        for (int i = 0; i < 8 && !flagged; i++) {
            flagged = combat.assessReach(id, new NormalizedCombatState(6.5, 40, 300, false, false)).failed();
        }
        assertTrue(flagged, "repeated 6.5m reach must flag");
    }

    @Test
    public void singleFarHitNeverFlags() {
        CombatChecks combat = new CombatChecks(cfg);
        UUID id = UUID.randomUUID();
        assertFalse(combat.assessReach(id, new NormalizedCombatState(6.5, 40, 300, false, false)).failed(),
                "one far hit in isolation must not flag");
    }

    @Test
    public void machineClickTimingDetected() {
        CombatChecks combat = new CombatChecks(cfg);
        UUID id = UUID.randomUUID();
        boolean flagged = false;
        // Metronome 90ms: mean<120, sd<6 → machine.
        long now = 1_000_000L;
        for (int i = 0; i < 70 && !flagged; i++) {
            now += 90;
            CheckResult r = combat.assessClick(id, now);
            if (r.failed() && r.detection().contains("AutoClicker")) flagged = true;
        }
        assertTrue(flagged, "metronome clicking must flag");
    }

    @Test
    public void inhumanHitRateDetected() {
        CombatChecks combat = new CombatChecks(cfg);
        UUID id = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        boolean flagged = false;
        long now = 2_000_000L;
        for (int i = 0; i < 30 && !flagged; i++) {
            now += 20; // 20ms between damaging hits: inhuman sustained
            if (combat.assessAura(id, now, target).failed()) flagged = true;
        }
        assertTrue(flagged, "inhuman hit rate must flag");
    }
}
