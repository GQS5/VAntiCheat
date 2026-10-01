package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeRegistryTest {
    @Test
    void indexesDefinitionsAndAppliesExplicitPolicies() {
        ProbeDefinition manual = probe("manual", true, true, false, ProbeVerificationStatus.UNVERIFIED);
        ProbeDefinition automatic = probe("automatic", true, false, true, ProbeVerificationStatus.VERIFIED);
        ProbeDefinition disabled = probe("disabled", false, true, true, ProbeVerificationStatus.VERIFIED);
        ProbeRegistry registry = ProbeRegistry.of(List.of(manual, automatic, disabled));

        assertEquals(manual, registry.find("manual"));
        assertEquals(manual, registry.get("manual"));
        assertTrue(registry.contains("manual"));
        assertTrue(!registry.contains("missing"));
        assertEquals(List.of(manual, automatic, disabled), registry.all());
        assertEquals(List.of(manual, automatic), registry.enabled());
        assertEquals(List.of(manual), registry.manual());
        assertEquals(List.of(automatic), registry.automatic());
        assertEquals(List.of(automatic, disabled), registry.verified());
        assertTrue(registry.unverified().contains(manual));
        assertEquals(3, registry.size());
        assertEquals(2, registry.enabledCount());
        assertEquals(1, registry.manualCount());
        assertEquals(1, registry.automaticCount());
        assertEquals(2, registry.verifiedCount());
        assertEquals(1, registry.unverifiedCount());
    }

    @Test
    void duplicateIdsAreRejected() {
        ProbeDefinition first = probe("same", true, true, true, ProbeVerificationStatus.VERIFIED);
        ProbeDefinition second = probe("same", true, true, true, ProbeVerificationStatus.VERIFIED);

        assertThrows(IllegalArgumentException.class, () -> ProbeRegistry.of(List.of(first, second)));
    }

    @Test
    void requireReportsUnknownIds() {
        ProbeRegistry registry = ProbeRegistry.of(List.of(probe("known", true, true, true,
                ProbeVerificationStatus.VERIFIED)));

        assertThrows(IllegalArgumentException.class, () -> registry.require("unknown"));
    }

    private static ProbeDefinition probe(String id, boolean enabled, boolean manual, boolean automatic,
                                         ProbeVerificationStatus status) {
        return new ProbeDefinition(id, id, "key." + id, ProbeMode.TRANSLATE, "fallback." + id,
                enabled, manual, automatic, status);
    }
}
