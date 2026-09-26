package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationLoaderTest {
    @Test
    void defaultsAreMinimalAndDeterministic() {
        assertEquals(new FoundationConfig(true, false, true),
                ConfigurationLoader.parse("vanticheat:\n  enabled: true\n  debug: false\n"));
    }

    @Test
    void knownValuesAreLoadedAndUnknownSectionsAreIgnored() {
        FoundationConfig config = ConfigurationLoader.parse("""
                vanticheat:
                  enabled: false
                  debug: true
                detection:
                  enabled: true
                """);

        assertEquals(new FoundationConfig(false, true, true), config);
    }

    @Test
    void invalidKnownValueIsRejectedByTheParser() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationLoader.parse("vanticheat:\n  enabled: maybe\n"));
    }
}
