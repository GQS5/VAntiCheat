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
 * Legitimate-behavior FP suite: none of these may produce CheckResult.failed().
 * Covers: sprint, walk, jump arcs, high ping, teleport step, knockback-adjacent
 * motion, legit CPS-rate combat at legal reach, water/ladder-adjacent (env-exempt
 * paths are server-side; here we assert cheap-path safety with null world).
 */
public class FalsePositiveSuiteTest {

    private final VConfig cfg = new VConfig(null, null);
    private final MovementChecks move = new MovementChecks(cfg);
    private final CombatChecks combat = new CombatChecks(cfg);

    private int runMoves(BotHarness.Bot b, double stepX, double stepY, double stepZ, int n) {
        int fails = 0;
        for (int i = 0; i < n; i++) {
            Location from = b.loc.clone();
            b.loc.add(stepX, stepY, stepZ);
            if (move.evaluate(b.player, from, b.loc.clone()).failed()) fails++;
        }
        return fails;
    }

    @Test
    public void sprintAndWalkAreClean() {
        BotHarness.Bot b = new BotHarness.Bot();
        b.sprinting = true;
        assertEquals(0, runMoves(b, 0.28, 0, 0, 2000), "sprint FP");
        b.sprinting = false;
        assertEquals(0, runMoves(b, 0.22, 0, 0, 2000), "walk FP");
    }

    @Test
    public void jumpArcsAreClean() {
        BotHarness.Bot b = new BotHarness.Bot();
        b.sprinting = true;
        b.onGround = false;
        // Jump arc: up then down, moderate horizontal (never hover: dy varies).
        double[] dys = {0.35, 0.28, 0.18, 0.05, -0.1, -0.25, -0.4, -0.5};
        int fails = 0, k = 0;
        for (int i = 0; i < 800; i++) {
            Location from = b.loc.clone();
            b.loc.add(0.3, dys[k++ % dys.length], 0);
            if (move.evaluate(b.player, from, b.loc.clone()).failed()) fails++;
        }
        assertEquals(0, fails, "jump arc FP");
    }

    @Test
    public void highPingNeverFlags() {
        BotHarness.Bot b = new BotHarness.Bot();
        b.ping = 350; // severe lag → suppression path
        assertEquals(0, runMoves(b, 0.9, 0, 0, 500), "high-ping speed FP");
        // High but sub-severe ping with legal movement.
        b.ping = 220;
        assertEquals(0, runMoves(b, 0.28, 0, 0, 500), "220ms sprint FP");
    }

    @Test
    public void singleTeleportStepIsClean() {
        BotHarness.Bot b = new BotHarness.Bot();
        Location from = b.loc.clone();
        b.loc.add(0, 5, 0); // single teleport-like step; step needs 3 repeats
        assertFalse(move.evaluate(b.player, from, b.loc.clone()).failed(), "teleport step FP");
    }

    @Test
    public void legalCombatNeverFlags() {
        CombatChecks combat = new CombatChecks(cfg);
        UUID id = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        long now = 5_000_000L;
        for (int i = 0; i < 60; i++) {
            now += 300; // legal swing timing
            assertFalse(combat.assessReach(id, new NormalizedCombatState(2.5, 50, 300, false, false)).failed(),
                    "legal reach FP at hit " + i);
            assertFalse(combat.assessAura(id, now, target).failed(), "legal aura FP at hit " + i);
        }
    }

    @Test
    public void fastButHumanClickingNeverFlags() {
        CombatChecks combat = new CombatChecks(cfg);
        UUID id = UUID.randomUUID();
        java.util.Random rnd = new java.util.Random(7);
        // ~8 CPS with human jitter (sd >> 6ms).
        long now = 9_000_000L;
        for (int i = 0; i < 60; i++) {
            now += 100 + rnd.nextInt(70);
            assertFalse(combat.assessClick(id, now).failed(), "human click FP at " + i);
        }
    }
}
