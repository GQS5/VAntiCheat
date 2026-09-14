package com.hexa.vanticheat.qa;

import com.hexa.vanticheat.checks.movement.MovementChecks;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.evidence.CorrelationEngine;
import com.hexa.vanticheat.evidence.ReplayBuffer;
import com.hexa.vanticheat.evidence.SignalDeduplicator;
import com.hexa.vanticheat.simulation.WorldReplica;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance + lifecycle stress (headless): hot-path latency percentiles,
 * 160-bot join/move/quit churn with full purge, dedup flood collapse,
 * replay/replica boundedness. Prints measured numbers to stdout.
 */
public class PerfAndLifecycleTest {

    @Test
    public void hotPathLatencyPercentiles() {
        VConfig cfg = new VConfig(null, null);
        MovementChecks move = new MovementChecks(cfg);
        BotHarness.Bot b = new BotHarness.Bot();
        int n = 120_000;
        long[] ts = new long[n];
        for (int i = 0; i < n; i++) {
            Location from = b.loc.clone();
            b.loc.add(0.28, 0, 0);
            long t0 = System.nanoTime();
            move.evaluate(b.player, from, b.loc.clone());
            ts[i] = System.nanoTime() - t0;
        }
        double[] p = BotHarness.percentiles(ts);
        System.out.printf("[PERF] movement hot-path us p50=%.2f p95=%.2f p99=%.2f max=%.2f%n",
                p[0], p[1], p[2], p[3]);
        assertTrue(p[2] < 500, "p99 must stay under 500us, was " + p[2]);
        assertTrue(p[3] < 5000, "max must stay under 5ms, was " + p[3]);
    }

    @Test
    public void lifecycleChurnLeavesNoState() {
        VConfig cfg = new VConfig(null, null);
        MovementChecks move = new MovementChecks(cfg);
        CorrelationEngine corr = new CorrelationEngine();
        SignalDeduplicator dedup = new SignalDeduplicator(1500);
        ReplayBuffer replay = new ReplayBuffer(10);
        WorldReplica replica = new WorldReplica();
        // 160 bots × join/move×50/quit with full purge (mirrors CheckManager.onQuit).
        for (int round = 0; round < 8; round++) {
            for (int i = 0; i < 20; i++) {
                BotHarness.Bot b = new BotHarness.Bot();
                for (int m = 0; m < 50; m++) {
                    Location from = b.loc.clone();
                    b.loc.add(0.28, 0, 0);
                    move.evaluate(b.player, from, b.loc.clone());
                    corr.observe(b.id, "movement", "step");
                    dedup.shouldProcess(b.id, "movement|step|1");
                    replay.offer(b.id, "move", 0.28, 0, 0, "walk");
                    replica.observe(b.id, m, 64, 0, "STONE");
                }
                move.purge(b.id);
                corr.purge(b.id);
                dedup.purge(b.id);
                replay.purge(b.id);
                replica.purge(b.id);
                assertEquals(0, corr.diversity(b.id), "correlation leak");
                assertTrue(replay.snapshot(b.id, 10).isEmpty(), "replay leak");
            }
        }
    }

    @Test
    public void dedupFloodCollapses() {
        SignalDeduplicator d = new SignalDeduplicator(60_000);
        UUID id = UUID.randomUUID();
        int admitted = 0;
        for (int i = 0; i < 10_000; i++) if (d.shouldProcess(id, "reach|hit|6")) admitted++;
        System.out.println("[PERF] dedup admitted " + admitted + "/10000 identical signals");
        assertEquals(1, admitted, "flood must collapse to 1");
    }
}
