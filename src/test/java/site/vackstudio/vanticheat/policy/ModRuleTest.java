package site.vackstudio.vanticheat.policy;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.client.ModRecord;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModRuleTest {
    @Test void testExactIdMatch() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        ModRule rule = new ModRule("freecam", config);
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        MatchResult result = rule.match(mod);
        assertTrue(result.isMatch());
        assertEquals("freecam", result.getBlockedRuleId());
        assertEquals("identifier", result.getMatchedField());
    }
    @Test void testDisabledRule() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = false;
        config.identifiers = List.of("freecam");
        ModRule rule = new ModRule("freecam", config);
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        assertFalse(rule.match(mod).isMatch());
    }
    @Test void testNoMatch() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        ModRule rule = new ModRule("freecam", config);
        ModRecord mod = new ModRecord("freedom", "Freedom", "1.0", "Fabric", "", "", "", "");
        assertFalse(rule.match(mod).isMatch());
    }
    @Test void testNormalization() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.names = List.of("free_cam");
        ModRule rule = new ModRule("freecam", config);
        ModRecord mod = new ModRecord("x", "Free Cam", "1.0", "Fabric", "", "", "", "");
        assertTrue(rule.match(mod).isMatch());
    }
    @Test void testFalseSubstringMatch() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        ModRule rule = new ModRule("freecam", config);
        ModRecord mod = new ModRecord("freedom", "Freedom Mod", "1.0", "Fabric", "", "", "", "");
        assertFalse(rule.match(mod).isMatch());
    }
}
