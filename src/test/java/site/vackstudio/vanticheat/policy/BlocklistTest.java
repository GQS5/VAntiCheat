package site.vackstudio.vanticheat.policy;

import site.vackstudio.vanticheat.client.ModRecord;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import site.vackstudio.vanticheat.config.PluginConfig;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class BlocklistTest {
    public static void main(String[] args) {
        testExactIdMatch();
        testDisabledRule();
        testMultipleRules();
        testFalseSubstringMatch();
        testUnknownMod();
        testLegitimateMod();
        testNoMatch();
        System.out.println("All Blocklist tests passed.");
    }

    static void testExactIdMatch() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        config.names = List.of(); config.versions = List.of(); config.loaders = List.of(); config.jarSha256s = List.of();
        Blocklist blocklist = new Blocklist(createConfig(config));
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        MatchResult result = blocklist.check(mod);
        assert result.isMatch() : "Should match freecam by ID";
    }

    static void testDisabledRule() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = false;
        config.identifiers = List.of("freecam");
        config.names = List.of(); config.versions = List.of(); config.loaders = List.of(); config.jarSha256s = List.of();
        Blocklist blocklist = new Blocklist(createConfig(config));
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        MatchResult result = blocklist.check(mod);
        assert !result.isMatch() : "Disabled rule should not match";
    }

    static void testMultipleRules() {
        ModRuleConfig config1 = new ModRuleConfig();
        config1.enabled = true;
        config1.identifiers = List.of("freecam");
        config1.names = List.of(); config1.versions = List.of(); config1.loaders = List.of(); config1.jarSha256s = List.of();
        ModRuleConfig config2 = new ModRuleConfig();
        config2.enabled = true;
        config2.identifiers = List.of("xray");
        config2.names = List.of(); config2.versions = List.of(); config2.loaders = List.of(); config2.jarSha256s = List.of();
        Blocklist blocklist = new Blocklist(createConfig(config1, config2));
        ModRecord mod1 = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        ModRecord mod2 = new ModRecord("xray", "XRay", "1.0", "Fabric", "", "", "", "");
        assert blocklist.check(mod1).isMatch();
        assert blocklist.check(mod2).isMatch();
    }

    static void testFalseSubstringMatch() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        config.names = List.of(); config.versions = List.of(); config.loaders = List.of(); config.jarSha256s = List.of();
        Blocklist blocklist = new Blocklist(createConfig(config));
        ModRecord mod = new ModRecord("freedom", "Freedom Mod", "1.0", "Fabric", "", "", "", "");
        MatchResult result = blocklist.check(mod);
        assert !result.isMatch() : "Should NOT match by substring; exact ID only";
    }

    static void testUnknownMod() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        config.names = List.of(); config.versions = List.of(); config.loaders = List.of(); config.jarSha256s = List.of();
        Blocklist blocklist = new Blocklist(createConfig(config));
        ModRecord mod = new ModRecord("unknown", "Unknown", "1.0", "Fabric", "", "", "", "");
        MatchResult result = blocklist.check(mod);
        assert !result.isMatch() : "Unknown mod should not match";
    }

    static void testLegitimateMod() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        config.names = List.of(); config.versions = List.of(); config.loaders = List.of(); config.jarSha256s = List.of();
        Blocklist blocklist = new Blocklist(createConfig(config));
        ModRecord mod = new ModRecord("journeymap", "Journeymap", "5.0", "Fabric", "", "", "", "");
        MatchResult result = blocklist.check(mod);
        assert !result.isMatch() : "Legitimate mod should not match";
    }

    static void testNoMatch() {
        Blocklist blocklist = new Blocklist(PluginConfig.defaults());
        ModRecord mod = new ModRecord("journeymap", "Journeymap", "5.0", "Fabric", "", "", "", "");
        MatchResult result = blocklist.check(mod);
        assert result.getState() == MatchResult.State.NO_MATCH : "Should return NO_MATCH";
    }

    private static PluginConfig createConfig(ModRuleConfig... configs) {
        PluginConfig config = PluginConfig.defaults();
        Map<String, ModRuleConfig> map = new HashMap<>();
        for (int i = 0; i < configs.length; i++) {
            map.put("rule" + i, configs[i]);
        }
        config.setModRules(map);
        return config;
    }
}
