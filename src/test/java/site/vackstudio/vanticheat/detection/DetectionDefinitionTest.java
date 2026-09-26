package site.vackstudio.vanticheat.detection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionDefinitionTest {
    @Test
    void acceptsGenericEnabledDefinition() {
        assertDoesNotThrow(() -> definition("sample"));
    }

    @Test
    void rejectsInvalidIdAndBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionDefinition("Sample", "Sample", "", DetectionCategory.CLIENT,
                        DetectionSeverity.INFO, true));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionDefinition("sample", " ", "", DetectionCategory.CLIENT,
                        DetectionSeverity.INFO, true));
    }

    private static DetectionDefinition definition(String id) {
        return new DetectionDefinition(id, "Sample", "Generic test definition",
                DetectionCategory.CLIENT, DetectionSeverity.INFO, true);
    }
}
