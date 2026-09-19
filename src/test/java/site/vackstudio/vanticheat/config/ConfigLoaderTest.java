package site.vackstudio.vanticheat.config;

import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigLoaderTest {

    public static void main(String[] args) throws Exception {
        testDefaultConfig();
        testMalformedConfigFallback();
        testMissingConfigFallback();
        testModRuleParsing();
        System.out.println("All ConfigLoader tests passed.");
    }

    static void testDefaultConfig() {
        PluginConfig config = PluginConfig.defaults();
        assert config.isGeneralEnabled() : "Should be enabled by default";
        assert config.isVerificationEnabled() : "Verification should be enabled by default";
        assert config.getTimeoutMs() == 3000 : "Timeout should be 3000ms";
        assert config.getMaxReportBytes() == 65535 : "Max report bytes should be 65535";
        assert config.getProtocolVersion() == 1 : "Protocol version should be 1";
    }

    static void testMalformedConfigFallback() throws Exception {
        Path tempDir = Files.createTempDirectory("vanticheat-test-");
        try {
            Path configPath = tempDir.resolve("config.yml");
            Files.writeString(configPath, "general:\n  enabled: invalid\nverification:\n  timeout-ms: not-a-number\n");
            ConfigLoader loader = new ConfigLoader(tempDir);
            PluginConfig config = loader.load();
            assert config != null : "Config should not be null";
        } finally {
            deleteDir(tempDir);
        }
    }

    static void testMissingConfigFallback() throws Exception {
        Path tempDir = Files.createTempDirectory("vanticheat-test-");
        try {
            ConfigLoader loader = new ConfigLoader(tempDir);
            PluginConfig config = loader.load();
            assert config != null : "Config should not be null";
            assert config.isGeneralEnabled() : "Should use defaults";
        } finally {
            deleteDir(tempDir);
        }
    }

    static void testModRuleParsing() throws Exception {
        Path tempDir = Files.createTempDirectory("vanticheat-test-");
        try {
            String yaml = "mods:\n  freecam:\n    enabled: true\n    action: kick\n    identifiers:\n      - freecam\n    names:\n      - Freecam\n";
            Path configPath = tempDir.resolve("config.yml");
            Files.writeString(configPath, yaml);
            ConfigLoader loader = new ConfigLoader(tempDir);
            PluginConfig config = loader.load();
            assert config.getModRules() != null : "Mod rules should not be null";
            assert config.getModRules().containsKey("freecam") : "Should have freecam rule";
        } finally {
            deleteDir(tempDir);
        }
    }

    static void deleteDir(Path dir) throws Exception {
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception e) {}
            });
        }
    }
}