package site.vackstudio.vanticheat.policy;

import site.vackstudio.vanticheat.client.ModRecord;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class ModRule {

    private final String ruleId;
    private final boolean enabled;
    private final String action;
    private final Set<String> identifiers;
    private final Set<String> names;
    private final Set<String> versions;
    private final Set<String> loaders;
    private final Set<String> jarSha256s;

    public ModRule(String ruleId, ModRuleConfig config) {
        this.ruleId = ruleId;
        this.action = config.action;
        this.enabled = config.enabled;
        this.identifiers = new HashSet<>(config.identifiers != null ? config.identifiers : List.of());
        this.names = normalizeSet(config.names != null ? config.names : List.of());
        this.versions = new HashSet<>(config.versions != null ? config.versions : List.of());
        this.loaders = new HashSet<>(config.loaders != null ? config.loaders : List.of());
        this.jarSha256s = new HashSet<>(config.jarSha256s != null ? config.jarSha256s : List.of());
    }

    public MatchResult match(ModRecord mod) {
        if (!enabled) return MatchResult.noMatch();

        if (identifiers.contains(mod.getModId())) {
            return new MatchResult(ruleId, mod.getName(), "identifier", mod.getModId(), this, MatchResult.State.MATCHED);
        }

        String normalizedName = normalize(mod.getName());
        if (names.contains(normalizedName)) {
            return new MatchResult(ruleId, mod.getName(), "name", mod.getName(), this, MatchResult.State.MATCHED);
        }

        if (!mod.getVersion().isEmpty() && versions.contains(mod.getVersion())) {
            return new MatchResult(ruleId, mod.getName(), "version", mod.getVersion(), this, MatchResult.State.MATCHED);
        }

        if (!mod.getLoader().isEmpty() && loaders.contains(mod.getLoader())) {
            return new MatchResult(ruleId, mod.getName(), "loader", mod.getLoader(), this, MatchResult.State.MATCHED);
        }

        if (!mod.getJarSha256().isEmpty() && jarSha256s.contains(mod.getJarSha256())) {
            return new MatchResult(ruleId, mod.getName(), "fingerprint", mod.getJarSha256(), this, MatchResult.State.MATCHED);
        }

        return MatchResult.noMatch();
    }

    public boolean isEnabled() { return enabled; }
    public String getRuleId() { return ruleId; }
    public String getAction() { return action; }
    public Set<String> getIdentifiers() { return identifiers; }
    public Set<String> getNames() { return names; }
    public Set<String> getVersions() { return versions; }
    public Set<String> getLoaders() { return loaders; }
    public Set<String> getJarSha256s() { return jarSha256s; }

    private static Set<String> normalizeSet(List<String> list) {
        Set<String> result = new HashSet<>();
        for (String s : list) {
            result.add(normalize(s));
        }
        return result;
    }

    private static String normalize(String s) {
        return s.toLowerCase().trim().replaceAll("[\\s_-]+", "_");
    }
}