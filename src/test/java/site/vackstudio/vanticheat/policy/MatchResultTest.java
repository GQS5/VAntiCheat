package site.vackstudio.vanticheat.policy;

import site.vackstudio.vanticheat.client.ModRecord;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import java.util.List;

public class MatchResultTest {
    public static void main(String[] args) {
        testNoMatch();
        testMatch();
        testStateValues();
        testToString();
        System.out.println("All MatchResult tests passed.");
    }

    static void testNoMatch() {
        MatchResult result = MatchResult.noMatch();
        assert !result.isMatch();
        assert result.getState() == MatchResult.State.NO_MATCH;
        assert result.getBlockedRuleId().equals("");
    }

    static void testMatch() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        config.names = List.of(); config.versions = List.of(); config.loaders = List.of(); config.jarSha256s = List.of();
        ModRule rule = new ModRule("freecam", config);
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        MatchResult result = rule.match(mod);
        assert result.isMatch();
        assert result.getBlockedRuleId().equals("freecam");
        assert "identifier".equals(result.getMatchedField());
    }

    static void testStateValues() {
        assert MatchResult.State.MATCHED != MatchResult.State.NO_MATCH;
    }

    static void testToString() {
        MatchResult result = MatchResult.noMatch();
        String s = result.toString();
        assert s.contains("NO_MATCH");
    }
}
