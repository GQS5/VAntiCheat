package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnforcementConfigTest {
    @Test
    void loadsEnforcementSettings() {
        assertEquals(new EnforcementConfig(false, "No access"), EnforcementConfig.parse("""
                enforcement:
                  enabled: false
                  confirmed-detection:
                    message: "No access"
                """));
    }
}
