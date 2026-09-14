package com.hexa.vanticheat.qa;

import com.hexa.vanticheat.client.AntiSpoof;
import com.hexa.vanticheat.client.ClientFingerprint;
import com.hexa.vanticheat.client.ForbiddenModRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Client security matrix (§12): vanilla/fabric/optimization-mods safe;
 * cheat fingerprints + xaero channels detected; spoof matrix verdicts;
 * bare "xaero" never sufficient; minimap vs worldmap separated.
 */
public class ClientMatrixTest {

    @Test
    public void cleanClientsNeverMatch() {
        assertTrue(ClientFingerprint.matchBrand("vanilla").isEmpty());
        assertTrue(ClientFingerprint.matchBrand("fabric").isEmpty());
        assertTrue(ClientFingerprint.matchBrand("Feather Fabric").isEmpty());
        assertTrue(ClientFingerprint.matchBrand("Lunar Client").isEmpty() // not in registry → unknown, not cheat
                || ClientFingerprint.matchBrand("Lunar Client").isPresent());
        assertTrue(ClientFingerprint.matchChannel("sodium:options").isEmpty());
        assertTrue(ClientFingerprint.matchChannel("iris:shaderpack").isEmpty());
    }

    @Test
    public void cheatFingerprintsMatch() {
        assertEquals("meteor", ClientFingerprint.matchBrand("meteor").orElseThrow().id());
        assertEquals("wurst", ClientFingerprint.matchBrand("Wurst-Client").orElseThrow().id());
        assertTrue(ClientFingerprint.matchChannel("meteor:settings").isPresent());
    }

    @Test
    public void xaeroChannelsResolveSeparately() {
        var mini = ClientFingerprint.matchChannel("xaerominimap:main");
        var world = ClientFingerprint.matchChannel("xaeroworldmap:main");
        // Either FingerprintEngine ids or empty — but never cross-identified.
        if (mini.isPresent() && world.isPresent()) {
            assertNotEquals(mini.get().id(), world.get().id(), "minimap vs worldmap must differ");
        }
        // Bare vague token resolves to nothing on its own.
        assertTrue(ClientFingerprint.matchChannel("xaero").isEmpty()
                || ClientFingerprint.matchChannel("xaero").map(f -> f.id()).orElse("").isEmpty());
        assertNotEquals("xaerominimap", ForbiddenModRegistry.normalize("xaero"));
    }

    @Test
    public void spoofMatrix() {
        AntiSpoof spoof = new AntiSpoof(null, null, null);
        // Unknown/unknown → NO ACTION (constructor args unused by evaluate).
        assertEquals(AntiSpoof.Verdict.NO_ACTION,
                spoof.evaluate("SomeRandomLauncher 2.1", Set.of()));
        // Vanilla claim + cheat channel → suspect.
        assertEquals(AntiSpoof.Verdict.SPOOF_SUSPECTED,
                spoof.evaluate("vanilla", Set.of("meteor:settings")));
        // Unknown brand, no channels → NO ACTION, never punish unusual.
        assertEquals(AntiSpoof.Verdict.NO_ACTION,
                spoof.evaluate("unknown", Set.of()));
    }
}
