package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorDetectionConfigTest {
    @Test
    void parsesObserveOnlyReachConfig() {
        BehaviorDetectionConfig config = BehaviorDetectionConfig.parse("""
                behavior-detection:
                  enabled: true
                  reach:
                    enabled: false
                    mode: observe
                  combat:
                    enabled: true
                    killaura:
                      enabled: false
                      mode: observe
                    autoclicker:
                      enabled: false
                      mode: observe
                  movement:
                    enabled: true
                    fly:
                      enabled: false
                      mode: observe
                    no-fall:
                      enabled: false
                      mode: observe
                    speed:
                      enabled: false
                      mode: observe
                """);

        assertTrue(config.enabled());
        assertFalse(config.reachEnabled());
        assertEquals("observe", config.reachMode());
        assertTrue(config.combatEnabled());
        assertFalse(config.killauraEnabled());
        assertEquals("observe", config.killauraMode());
        assertFalse(config.autoclickerEnabled());
        assertEquals("observe", config.autoclickerMode());
        assertTrue(config.movementEnabled());
        assertFalse(config.flyEnabled());
        assertEquals("observe", config.flyMode());
        assertFalse(config.noFallEnabled());
        assertEquals("observe", config.noFallMode());
        assertFalse(config.speedEnabled());
        assertEquals("observe", config.speedMode());
    }
}
