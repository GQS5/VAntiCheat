package site.vackstudio.vanticheat.config;

import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public record ClientDetectionConfig(
        boolean enabled,
        boolean doubleCheck,
        long timeoutTicks,
        long betweenProbeTicks,
        List<ProbeDefinition> probes,
        boolean autoCheckOnJoin,
        long autoCheckDelayTicks,
        boolean firstJoinOnly,
        List<String> autoProbeIds,
        int maxConcurrentAutoChecks) {
    public ClientDetectionConfig(boolean enabled, boolean doubleCheck, long timeoutTicks,
                                 long betweenProbeTicks, List<ProbeDefinition> probes) {
        this(enabled, doubleCheck, timeoutTicks, betweenProbeTicks, probes,
                false, 1, false, probes.stream().map(ProbeDefinition::id).toList(), 32);
    }

    public ClientDetectionConfig {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        if (betweenProbeTicks < 0) throw new IllegalArgumentException("betweenProbeTicks cannot be negative");
        if (autoCheckDelayTicks < 0) throw new IllegalArgumentException("autoCheckDelayTicks cannot be negative");
        if (maxConcurrentAutoChecks < 1) throw new IllegalArgumentException("maxConcurrentAutoChecks must be positive");
        probes = List.copyOf(probes);
        autoProbeIds = List.copyOf(autoProbeIds);
    }

    public static ClientDetectionConfig disabled() {
        return new ClientDetectionConfig(false, true, 40, 1, List.of(), false, 1, false, List.of(), 32);
    }

    public static ClientDetectionConfig load(Path dataDirectory, Logger logger) {
        if (dataDirectory == null) return disabled();
        Path file = dataDirectory.resolve("client-detection.yml");
        if (!Files.isRegularFile(file)) return disabled();
        try {
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Unable to load client-detection.yml; clientDetection=UNAVAILABLE reason=INVALID_CONFIG\n"
                    + exception.getMessage());
            return disabled();
        }
    }

    /** Strict load used by reload preflight; it never replaces a live configuration. */
    public static ClientDetectionConfig loadStrict(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve("client-detection.yml");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("file=client-detection.yml\npath=<file>\nexpected=file\nactual=missing");
        }
        try {
            return parse(Files.readString(file));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("file=client-detection.yml\npath=<unknown>\nreason="
                    + exception.getMessage(), exception);
        }
    }

    static ClientDetectionConfig parse(String content) {
        boolean enabled = false;
        boolean doubleCheck = true;
        long timeoutTicks = 40;
        long betweenProbeTicks = 1;
        boolean autoCheckOnJoin = false;
        long autoCheckDelayTicks = 1;
        boolean firstJoinOnly = false;
        int maxConcurrentAutoChecks = 32;
        boolean inRoot = false;
        boolean inProbes = false;
        boolean inAutoCheck = false;
        boolean inAutoProbeIds = false;
        String currentId = null;
        Map<String, Map<String, String>> rawProbes = new LinkedHashMap<>();
        List<String> autoProbeIds = new ArrayList<>();

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            if (indent == 0) {
                inRoot = trimmed.equals("client-detection:");
                inProbes = false;
                inAutoCheck = false;
                inAutoProbeIds = false;
                currentId = null;
                continue;
            }
            if (!inRoot) continue;
            if (indent == 2) {
                if (trimmed.equals("probes:")) {
                    inProbes = true;
                    inAutoCheck = false;
                    inAutoProbeIds = false;
                    currentId = null;
                    continue;
                }
                if (trimmed.equals("auto-check:")) {
                    inProbes = false;
                    inAutoCheck = true;
                    inAutoProbeIds = false;
                    currentId = null;
                    continue;
                }
                inProbes = false;
                inAutoCheck = false;
                inAutoProbeIds = false;
                String[] pair = pair(trimmed);
                switch (pair[0]) {
                    case "enabled" -> enabled = bool(pair[1], "client-detection.enabled");
                    case "double-check" -> doubleCheck = bool(pair[1], "client-detection.double-check");
                    case "timeout-ticks" -> timeoutTicks = Long.parseLong(unquote(pair[1]));
                    case "between-probe-ticks" -> betweenProbeTicks = Long.parseLong(unquote(pair[1]));
                    default -> { }
                }
                continue;
            }
            if (inAutoCheck) {
                if (indent == 4 && trimmed.equals("probes:")) {
                    inAutoProbeIds = true;
                    continue;
                }
                if (indent == 4 && trimmed.startsWith("- ")) {
                    autoProbeIds.add(unquote(trimmed.substring(2).trim()));
                    continue;
                }
                if (indent == 4) {
                    inAutoProbeIds = false;
                    String[] pair = pair(trimmed);
                    switch (pair[0]) {
                        case "on-join" -> autoCheckOnJoin = bool(pair[1], "client-detection.auto-check.on-join");
                        case "delay-ticks" -> autoCheckDelayTicks = Long.parseLong(unquote(pair[1]));
                        case "first-join-only" -> firstJoinOnly = bool(pair[1], "client-detection.auto-check.first-join-only");
                        case "max-concurrent" -> maxConcurrentAutoChecks = Integer.parseInt(unquote(pair[1]));
                        default -> { }
                    }
                    continue;
                }
                if (inAutoProbeIds && indent >= 6 && trimmed.startsWith("- ")) {
                    autoProbeIds.add(unquote(trimmed.substring(2).trim()));
                }
                continue;
            }
            if (!inProbes) continue;
            if (indent == 4) {
                if (!trimmed.endsWith(":")) throw new IllegalArgumentException("Invalid probe entry");
                currentId = trimmed.substring(0, trimmed.length() - 1).trim();
                if (rawProbes.putIfAbsent(currentId, new LinkedHashMap<>()) != null) {
                    throw new IllegalArgumentException("Duplicate probe id: " + currentId);
                }
                continue;
            }
            if (indent >= 6 && currentId != null) {
                String[] pair = pair(trimmed);
                 rawProbes.get(currentId).put(pair[0], pair[1]);
            }
        }

        List<ProbeDefinition> probes = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : rawProbes.entrySet()) {
            Map<String, String> values = entry.getValue();
            String displayName = unquote(required(values, "display-name"));
            String key = unquote(required(values, "key"));
            ProbeMode mode = ProbeMode.valueOf(unquote(required(values, "mode")).toUpperCase(Locale.ROOT));
            boolean probeEnabled = bool(values.getOrDefault("enabled", "true"),
                    "client-detection.probes." + entry.getKey() + ".enabled");
            String fallback = unquote(values.getOrDefault("fallback", ""));
            ProbeVerificationStatus status = ProbeVerificationStatus.valueOf(
                    unquote(values.getOrDefault("verification", "UNVERIFIED")).toUpperCase(Locale.ROOT));
            probes.add(new ProbeDefinition(entry.getKey(), displayName, key, mode,
                    fallback, probeEnabled, status));
        }
        return new ClientDetectionConfig(enabled, doubleCheck, timeoutTicks, betweenProbeTicks, probes,
                autoCheckOnJoin, autoCheckDelayTicks, firstJoinOnly, autoProbeIds, maxConcurrentAutoChecks);
    }

    private static String[] pair(String value) {
        String[] pair = value.split(":", 2);
        if (pair.length != 2) throw new IllegalArgumentException("Invalid config entry");
        return new String[]{pair[0].trim(), stripInlineComment(pair[1].trim())};
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static boolean bool(String value, String path) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        String actual = isQuoted(value) ? "String" : value.matches("[-+]?\\d+(\\.\\d+)?") ? "Number" : "String";
        throw new IllegalArgumentException("file=client-detection.yml\npath=" + path
                + "\nexpected=boolean\nactual=" + actual);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isQuoted(String value) {
        return value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")));
    }

    private static String stripInlineComment(String value) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'' && !doubleQuoted) singleQuoted = !singleQuoted;
            if (character == '"' && !singleQuoted) doubleQuoted = !doubleQuoted;
            if (character == '#' && !singleQuoted && !doubleQuoted
                    && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) {
                return value.substring(0, index).stripTrailing();
            }
        }
        return value;
    }

    public List<ProbeDefinition> automaticProbes() {
        return autoProbeIds.stream()
                .map(id -> probes.stream().filter(probe -> probe.id().equals(id)).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(ProbeDefinition::enabled)
                .toList();
    }
}
