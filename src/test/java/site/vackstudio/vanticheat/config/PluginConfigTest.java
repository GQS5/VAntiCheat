package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PluginConfigTest {
    @Test void testDefaults() {
        PluginConfig config = PluginConfig.defaults();
        assertTrue(config.isGeneralEnabled());
        assertTrue(config.isVerificationEnabled());
        assertEquals(3000, config.getTimeoutMs());
        assertEquals(65535, config.getMaxReportBytes());
        assertEquals(1000, config.getMaxSessionCount());
        assertEquals(1, config.getProtocolVersion());
    }
    @Test void testSetters() {
        PluginConfig config = new PluginConfig();
        config.setGeneralEnabled(false);
        assertFalse(config.isGeneralEnabled());
        config.setTimeoutMs(5000);
        assertEquals(5000, config.getTimeoutMs());
    }
    @Test void testModRules() {
        PluginConfig config = new PluginConfig();
        assertNull(config.getModRules());
    }
}
