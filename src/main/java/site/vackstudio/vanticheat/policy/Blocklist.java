package site.vackstudio.vanticheat.policy;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import site.vackstudio.vanticheat.client.ModRecord;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import site.vackstudio.vanticheat.config.PluginConfig;

public class Blocklist {

    private final List<ModRule> rules;

    public Blocklist(PluginConfig config) {
        this.rules = new ArrayList<>();
        if (config.getModRules() != null) {
            for (Map.Entry<String, ModRuleConfig> entry : config.getModRules().entrySet()) {
                rules.add(new ModRule(entry.getKey(), entry.getValue()));
            }
        }
    }

    public List<ModRule> getRules() { return List.copyOf(rules); }

    public MatchResult check(ModRecord mod) {
        for (ModRule rule : rules) {
            if (!rule.isEnabled()) continue;
            MatchResult result = rule.match(mod);
            if (result.isMatch()) {
                return result;
            }
        }
        return MatchResult.noMatch();
    }

    public List<MatchResult> checkAll(List<ModRecord> mods) {
        List<MatchResult> results = new ArrayList<>();
        for (ModRecord mod : mods) {
            MatchResult result = check(mod);
            if (result.isMatch()) {
                results.add(result);
            }
        }
        return results;
    }

    public int ruleCount() { return rules.size(); }
    public int enabledRuleCount() { return (int) rules.stream().filter(ModRule::isEnabled).count(); }
}