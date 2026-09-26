package site.vackstudio.vanticheat.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.logging.Level;

/** Loads the intentionally small foundation YAML without a runtime YAML library. */
public final class ConfigurationLoader {
    private ConfigurationLoader() { }

    public static FoundationConfig load(Path dataDirectory, Logger logger) {
        if (dataDirectory == null) return FoundationConfig.defaults();
        Path file = dataDirectory.resolve("config.yml");
        if (!Files.isRegularFile(file)) return FoundationConfig.defaults();
        try {
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Unable to load config.yml; using foundation defaults", exception);
            return FoundationConfig.defaults();
        }
    }

    public static EnforcementConfig loadEnforcement(Path dataDirectory, Logger logger) {
        if (dataDirectory == null) return EnforcementConfig.defaults();
        Path file = dataDirectory.resolve("config.yml");
        if (!Files.isRegularFile(file)) return EnforcementConfig.defaults();
        try {
            return EnforcementConfig.parse(Files.readString(file));
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Unable to load enforcement config; enforcement disabled", exception);
            return new EnforcementConfig(false, EnforcementConfig.defaults().confirmedDetectionMessage());
        }
    }

    static FoundationConfig parse(String content) {
        boolean enabled = true;
        boolean debug = false;
        boolean detectionEnabled = true;
        boolean inVantiCheat = false;
        boolean inDetection = false;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            if (!line.startsWith(" ")) {
                inVantiCheat = line.trim().equals("vanticheat:");
                inDetection = line.trim().equals("detection:");
                continue;
            }
            if (!inVantiCheat && !inDetection) continue;
            String[] pair = line.trim().split(":", 2);
            if (pair.length != 2) throw new IllegalArgumentException("Invalid config entry");
            boolean value = parseBoolean(pair[1].trim());
            if (inVantiCheat) {
                switch (pair[0]) {
                    case "enabled" -> enabled = value;
                    case "debug" -> debug = value;
                    default -> { }
                }
            } else if (pair[0].equals("enabled")) {
                detectionEnabled = value;
            }
        }
        return new FoundationConfig(enabled, debug, detectionEnabled);
    }

    private static boolean parseBoolean(String value) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Expected boolean");
    }
}
