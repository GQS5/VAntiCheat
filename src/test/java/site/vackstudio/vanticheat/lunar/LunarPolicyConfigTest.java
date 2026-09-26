package site.vackstudio.vanticheat.lunar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LunarPolicyConfigTest {
    @TempDir Path temporaryDirectory;

    @Test
    void missingSectionFallsBackToSecureDefaults() {
        LunarPolicyConfig config = LunarPolicyConfig.parse("vanticheat:\n  enabled: true\n");

        assertTrue(config.enabled());
        assertTrue(config.minimapEnabled());
    }

    @Test
    void fullSectionParses() {
        LunarPolicyConfig config = LunarPolicyConfig.parse(
                "lunar:\n  enabled: true\n  minimap:\n    enabled: true\n    action: DISABLE\n");

        assertTrue(config.enabled());
        assertTrue(config.minimapEnabled());
    }

    @Test
    void disabledIntegrationAndPolicyAreRespected() {
        LunarPolicyConfig config = LunarPolicyConfig.parse(
                "lunar:\n  enabled: false\n  minimap:\n    enabled: false\n    action: DISABLE\n");

        assertFalse(config.enabled());
        assertFalse(config.minimapEnabled());
    }

    @Test
    void unknownActionIsRejectedWithPath() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LunarPolicyConfig.parse(
                        "lunar:\n  enabled: true\n  minimap:\n    enabled: true\n    action: KICK\n"));

        assertTrue(failure.getMessage().contains("lunar.minimap.action"));
    }

    @Test
    void invalidBooleanIsRejectedWithPath() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LunarPolicyConfig.parse("lunar:\n  enabled: yes\n"));

        assertTrue(failure.getMessage().contains("lunar.enabled"));
    }

    @Test
    void missingFileUsesDefaultsAndMalformedFileDisables() throws Exception {
        assertEquals(LunarPolicyConfig.defaults(),
                LunarPolicyConfig.load(temporaryDirectory.resolve("missing"), Logger.getAnonymousLogger()));

        Path directory = Files.createDirectory(temporaryDirectory.resolve("server"));
        Files.writeString(directory.resolve("config.yml"), "lunar:\n  enabled: yes\n");

        assertEquals(LunarPolicyConfig.disabled(),
                LunarPolicyConfig.load(directory, Logger.getAnonymousLogger()));
    }
}
