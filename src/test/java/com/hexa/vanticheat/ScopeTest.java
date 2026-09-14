package com.hexa.vanticheat;

import com.hexa.vanticheat.client.ClientFingerprint;
import com.hexa.vanticheat.client.ForbiddenModRegistry;
import com.hexa.vanticheat.client.KnownClients;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scope tests: vanilla/fabric+sodium must NOT match; Xaero channels must
 * resolve exactly; bare "xaero" must NOT match; ghost clients undetectable.
 */
public class ScopeTest {

    @Test
    public void vanillaNeverMatches() {
        assertTrue(ClientFingerprint.matchBrand("vanilla").isEmpty());
        assertTrue(ClientFingerprint.matchBrand("fabric").isEmpty());
        assertTrue(ClientFingerprint.matchBrand("Feather Fabric").isEmpty());
        assertTrue(ClientFingerprint.matchBrand(null).isEmpty());
    }

    @Test
    public void cheatBrandsMatch() {
        assertEquals("meteor", ClientFingerprint.matchBrand("meteor").orElseThrow().id());
        assertEquals("wurst", ClientFingerprint.matchBrand("Wurst-Client").orElseThrow().id());
    }

    @Test
    public void ghostClientsNotDetectableAlone() {
        var vape = KnownClients.ALL.stream().filter(f -> f.id().equals("vape")).findFirst().orElseThrow();
        assertFalse(vape.detectable());
    }

    @Test
    public void xaeroNeedsFullIdentifier() {
        // Bare "xaero" must never equal a full mod identifier.
        assertNotEquals("xaerominimap", ForbiddenModRegistry.normalize("xaero"));
        assertNotEquals("xaerominimap", ForbiddenModRegistry.normalize("xaero!!!"));
        assertEquals("xaerominimap", ForbiddenModRegistry.normalize("Xaero-Minimap"));
    }
}
