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
            logger.log(Level.WARNING, "Unable to load client-detection.yml; probing disabled", exception);
            return disabled();
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
                    case "enabled" -> enabled = bool(pair[1]);
                    case "double-check" -> doubleCheck = bool(pair[1]);
                    case "timeout-ticks" -> timeoutTicks = Long.parseLong(pair[1]);
                    case "between-probe-ticks" -> betweenProbeTicks = Long.parseLong(pair[1]);
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
                        case "on-join" -> autoCheckOnJoin = bool(pair[1]);
                        case "delay-ticks" -> autoCheckDelayTicks = Long.parseLong(pair[1]);
                        case "first-join-only" -> firstJoinOnly = bool(pair[1]);
                        case "max-concurrent" -> maxConcurrentAutoChecks = Integer.parseInt(pair[1]);
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
            String displayName = required(values, "display-name");
            String key = required(values, "key");
            ProbeMode mode = ProbeMode.valueOf(required(values, "mode").toUpperCase(Locale.ROOT));
            boolean probeEnabled = bool(values.getOrDefault("enabled", "true"));
            String fallback = values.getOrDefault("fallback", "");
            ProbeVerificationStatus status = ProbeVerificationStatus.valueOf(
                    values.getOrDefault("verification", "UNVERIFIED").toUpperCase(Locale.ROOT));
            probes.add(new ProbeDefinition(entry.getKey(), displayName, key, mode,
                    fallback, probeEnabled, status));
        }
        return new ClientDetectionConfig(enabled, doubleCheck, timeoutTicks, betweenProbeTicks, probes,
                autoCheckOnJoin, autoCheckDelayTicks, firstJoinOnly, autoProbeIds, maxConcurrentAutoChecks);
    }

    private static String[] pair(String value) {
        String[] pair = value.split(":", 2);
        if (pair.length != 2) throw new IllegalArgumentException("Invalid config entry");
        return new String[]{pair[0].trim(), unquote(pair[1].trim())};
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static boolean bool(String value) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Expected boolean");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
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
