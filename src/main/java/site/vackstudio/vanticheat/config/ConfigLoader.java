package site.vackstudio.vanticheat.config;

import org.yaml.snakeyaml.Yaml;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class ConfigLoader {

    private final Path basePath;

    public ConfigLoader(Path basePath) {
        this.basePath = basePath;
    }

    public PluginConfig load() throws Exception {
        Path configPath = basePath.resolve("config.yml");
        if (!Files.exists(configPath)) {
            Files.createDirectories(basePath);
            return PluginConfig.defaults();
        }

        Yaml yaml = new Yaml();
        Map<String, Object> raw;
        try (var in = Files.newInputStream(configPath)) {
            raw = yaml.load(in);
        }
        if (raw == null) {
            return PluginConfig.defaults();
        }
        return parseConfig(raw);
    }

    @SuppressWarnings("unchecked")
    private PluginConfig parseConfig(Map<String, Object> raw) {
        PluginConfig config = new PluginConfig();

        Map<String, Object> general = getMap(raw, "general");
        if (general != null) {
            config.setGeneralEnabled(bool(general.get("enabled"), true));
            config.setDebug(bool(general.get("debug"), false));
            config.setPluginName(str(general.get("plugin-name"), "VAntiCheat"));
            config.setPluginVersion(str(general.get("plugin-version"), "0.1.0"));
        }

        Map<String, Object> verification = getMap(raw, "verification");
        if (verification != null) {
            config.setVerificationEnabled(bool(verification.get("enabled"), true));
            config.setRequireClientVerifier(bool(verification.get("require-client-verifier"), true));
            config.setTimeoutMs(intVal(verification.get("timeout-ms"), 3000));
            config.setMaxReportBytes(intVal(verification.get("max-report-bytes"), 65535));
            config.setMaxSessionCount(intVal(verification.get("max-session-count"), 1000));
            config.setCleanupIntervalMs(intVal(verification.get("session-cleanup-interval-ms"), 60000));
            config.setProtocolVersion(intVal(verification.get("protocol-version"), 1));
        }

        Map<String, Object> mods = getMap(raw, "mods");
        if (mods != null) {
            config.setModRules(parseModRules(mods));
        }

        Map<String, Object> messages = getMap(raw, "messages");
        if (messages != null) {
            config.setVerificationRequired(str(messages.get("verification-required"), "Verification required."));
            config.setVerificationFailed(str(messages.get("verification-failed"), "Verification failed."));
            config.setForbiddenMod(str(messages.get("forbidden-mod"), "Forbidden mod detected."));
            config.setVerificationTimeout(str(messages.get("verification-timeout"), "Verification timed out."));
            config.setProtocolError(str(messages.get("protocol-error"), "Protocol error."));
        }

        return config;
    }

    private Map<String, ModRuleConfig> parseModRules(Map<String, Object> mods) {
        Map<String, ModRuleConfig> rules = new HashMap<>();
        for (Map.Entry<String, Object> entry : mods.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ruleData = (Map<String, Object>) entry.getValue();
                ModRuleConfig rule = new ModRuleConfig();
                rule.enabled = bool(ruleData.get("enabled"), true);
                rule.action = str(ruleData.get("action"), "kick");
                rule.identifiers = strList(ruleData.get("identifiers"));
                rule.names = strList(ruleData.get("names"));
                rule.versions = strList(ruleData.get("versions"));
                rule.loaders = strList(ruleData.get("loaders"));
                // Preferred keys are jarSha256 / jarSha256s; legacy "fingerprints" is
                // still accepted for backward compatibility and merged in.
                java.util.LinkedHashSet<String> hashes = new java.util.LinkedHashSet<>();
                hashes.addAll(strList(ruleData.get("jarSha256")));
                hashes.addAll(strList(ruleData.get("jarSha256s")));
                hashes.addAll(strList(ruleData.get("fingerprints")));
                rule.jarSha256s = new java.util.ArrayList<>(hashes);
                rules.put(entry.getKey(), rule);
            }
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(Object obj) {
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        return List.of();
    }

    private boolean bool(Object val, boolean def) {
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return def;
    }

    private int intVal(Object val, int def) {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) {}
        }
        return def;
    }

    private String str(Object val, String def) {
        if (val instanceof String) return (String) val;
        return def;
    }

    private Map<String, Object> getMap(Map<String, Object> raw, String key) {
        Object obj = raw.get(key);
        if (obj instanceof Map) return (Map<String, Object>) obj;
        return null;
    }
}