package com.hexa.vanticheat;

import com.hexa.vanticheat.client.FingerprintEngine;
import com.hexa.vanticheat.client.ForbiddenModRegistry;
import com.hexa.vanticheat.client.ModPolicy;
import com.hexa.vanticheat.core.ConfigSnapshot;
import com.hexa.vanticheat.core.SecurityProfile;
import com.hexa.vanticheat.evidence.CorrelationEngine;
import com.hexa.vanticheat.evidence.ReplayBuffer;
import com.hexa.vanticheat.evidence.SignalDeduplicator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.2 architecture tests: hot-path snapshot, dedup collapse, diversity,
 * allowlist, xaero separation, replay bound.
 */
public class Engine12Test {

    @Test
    public void snapshotDefaultsSane() {
        ConfigSnapshot s = ConfigSnapshot.defaults();
        assertTrue(s.behaviorEnabled);
        assertEquals(3.4, s.maxReach);
        assertEquals(SecurityProfile.STANDARD, s.profile);
    }

    @Test
    public void dedupCollapsesFlood() {
        SignalDeduplicator d = new SignalDeduplicator(60_000);
        UUID id = UUID.randomUUID();
        assertTrue(d.shouldProcess(id, "reach|hit|3"));
        for (int i = 0; i < 99; i++) assertFalse(d.shouldProcess(id, "reach|hit|3"));
    }

    @Test
    public void diversityRewardsIndependence() {
        CorrelationEngine c = new CorrelationEngine();
        UUID id = UUID.randomUUID();
        c.observe(id, "reach", "a");
        assertEquals(0, c.bonus(id)); // single family → no bonus
        c.observe(id, "rotation", "b");
        c.observe(id, "timing", "c");
        assertTrue(c.bonus(id) >= 8);
    }

    @Test
    public void allowedModsNeverForbidden() {
        // Sodium-like identifiers normalize distinctly; allowlist covers them.
        assertEquals("sodium", ForbiddenModRegistry.normalize("Sodium"));
        assertTrue(FingerprintEngine.allowedByDefault().contains("sodium"));
        assertTrue(FingerprintEngine.allowedByDefault().contains("lithium"));
        assertTrue(FingerprintEngine.allowedByDefault().contains("iris"));
    }

    @Test
    public void xaeroSeparated() {
        assertNotEquals("xaerominimap", ForbiddenModRegistry.normalize("xaero"));
        assertEquals("xaerominimap", ForbiddenModRegistry.normalize("Xaero-Minimap"));
        assertEquals("xaeroworldmap", ForbiddenModRegistry.normalize("Xaero-WorldMap"));
    }

    @Test
    public void replayBounded() {
        ReplayBuffer r = new ReplayBuffer(10);
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 200; i++) r.offer(id, "combat", i, 0, 0, "x");
        assertTrue(r.snapshot(id, 100).size() <= 64);
    }
}
