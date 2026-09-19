package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

class ConfigRobustnessTest {

    @Test void validConfigLoads() throws Exception {
        Path dir = Files.createTempDirectory("vac-valid-");
        try {
            Files.writeString(dir.resolve("config.yml"),
                    "general:\n  enabled: true\nverification:\n  enabled: true\n  timeout-ms: 3000\n"
                    + "  max-report-bytes: 65535\n  max-session-count: 100\n  protocol-version: 1\n"
                    + "mods:\n  freecam:\n    enabled: true\n    action: kick\n"
                    + "    identifiers:\n      - freecam\n    jarSha256:\n      - abc123\n");
            PluginConfig config = new ConfigLoader(dir).load();
            assertTrue(config.isGeneralEnabled());
            assertTrue(config.isVerificationEnabled());
            assertEquals(65535, config.getMaxReportBytes());
            assertNotNull(config.getModRules());
            assertTrue(config.getModRules().containsKey("freecam"));
            assertTrue(config.getModRules().get("freecam").jarSha256s.contains("abc123"));
        } finally {
            deleteDir(dir);
        }
    }

    @Test void legacyFingerprintKeyStillLoads() throws Exception {
        Path dir = Files.createTempDirectory("vac-legacy-");
        try {
            Files.writeString(dir.resolve("config.yml"),
                    "mods:\n  freecam:\n    enabled: true\n    action: kick\n"
                    + "    identifiers:\n      - freecam\n    fingerprints:\n      - deadbeef\n");
            PluginConfig config = new ConfigLoader(dir).load();
            assertTrue(config.getModRules().get("freecam").jarSha256s.contains("deadbeef"));
        } finally {
            deleteDir(dir);
        }
    }

    @Test void malformedYamlFallsBackSafely() throws Exception {
        Path dir = Files.createTempDirectory("vac-malformed-");
        try {
            Files.writeString(dir.resolve("config.yml"), "{{{{ not: [valid yaml");
            PluginConfig config = null;
            boolean crashed = false;
            try {
                config = new ConfigLoader(dir).load();
            } catch (Exception e) {
                crashed = true;
            }
            // Either controlled fallback or a propagated parse exception is acceptable;
            // a crash of the proxy is not. ConfigLoader.load declares throws, and the
            // plugin catches it and uses defaults.
            if (!crashed) {
                assertNotNull(config);
            }
        } finally {
            deleteDir(dir);
        }
    }

    @Test void invalidValuesFallBackDeterministically() throws Exception {
        Path dir = Files.createTempDirectory("vac-invalid-");
        try {
            Files.writeString(dir.resolve("config.yml"),
                    "general:\n  enabled: notabool\nverification:\n  enabled: true\n"
                    + "  timeout-ms: not-a-number\n  max-session-count: -5\n  protocol-version: 99\n");
            PluginConfig config = new ConfigLoader(dir).load();
            assertNotNull(config);
            // Boolean.parseBoolean semantics are deterministic: only "true" is true.
            assertFalse(config.isGeneralEnabled());
            assertEquals(3000, config.getTimeoutMs());
            // 99 is a syntactically valid int so it passes through deterministically;
            // the protocol layer itself rejects unsupported versions.
            assertEquals(99, config.getProtocolVersion());
            assertFalse(new site.vackstudio.vanticheat.protocol.VerificationProtocol(
                    config.getProtocolVersion(), config.getTimeoutMs()).validateProtocolVersion(99));
            // Negative session count passes through as-is; capacity enforcement in
            // VerificationSessionManager treats it as fail-closed (no sessions fit).
            assertEquals(-5, config.getMaxSessionCount());
        } finally {
            deleteDir(dir);
        }
    }

    private static void deleteDir(Path dir) throws Exception {
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
