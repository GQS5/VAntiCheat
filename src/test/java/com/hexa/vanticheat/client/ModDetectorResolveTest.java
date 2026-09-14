package com.hexa.vanticheat.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Xaero/mod channel resolution: exact mapping, minimap≠worldmap, bare token safe. */
public class ModDetectorResolveTest {

    private final ModDetector det = new ModDetector(null, null, null, null, null, null);

    @Test
    public void xaeroMinimapResolves() {
        assertEquals("xaeros_minimap", det.resolveModId("xaerominimap:main"));
        assertEquals("xaeros_minimap", det.resolveModId("xaerominimap_fair:main"));
    }

    @Test
    public void xaeroWorldmapResolvesSeparately() {
        assertEquals("xaeros_worldmap", det.resolveModId("xaeroworldmap:main"));
        assertNotEquals(det.resolveModId("xaerominimap:main"), det.resolveModId("xaeroworldmap:main"));
    }

    @Test
    public void bareVagueTokenResolvesToNothing() {
        assertNull(det.resolveModId("xaero"));
        assertNull(det.resolveModId("xaero:main"));
        assertNull(det.resolveModId(null));
    }

    @Test
    public void otherModsResolve() {
        assertEquals("journeymap", det.resolveModId("journeymap:main"));
        assertEquals("freecam", det.resolveModId("freecam:update"));
        assertNull(det.resolveModId("sodium:options"));
        assertNull(det.resolveModId("iris:shaderpack"));
        assertNull(det.resolveModId("minecraft:brand"));
    }
}
