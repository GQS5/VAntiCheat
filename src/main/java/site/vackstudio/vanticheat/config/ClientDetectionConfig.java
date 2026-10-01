package site.vackstudio.vanticheat.config;

import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeRegistry;
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
        ProbeRegistry registry,
        boolean autoCheckOnJoin,
        long autoCheckDelayTicks,
        boolean firstJoinOnly,
        int maxConcurrentAutoChecks,
        long shortTimeoutTicks,
        int shortTimeoutAfterConsecutiveTimeouts) {
    public ClientDetectionConfig(boolean enabled, boolean doubleCheck, long timeoutTicks,
                                 long betweenProbeTicks, List<ProbeDefinition> probes) {
        this(enabled, doubleCheck, timeoutTicks, betweenProbeTicks, ProbeRegistry.of(probes),
                false, 1, false, 32, timeoutTicks, 2);
    }

    public ClientDetectionConfig(boolean enabled, boolean doubleCheck, long timeoutTicks,
                                 long betweenProbeTicks, List<ProbeDefinition> probes,
                                 boolean autoCheckOnJoin, long autoCheckDelayTicks,
                                 boolean firstJoinOnly, List<String> ignoredAutoProbeIds,
                                 int maxConcurrentAutoChecks) {
        this(enabled, doubleCheck, timeoutTicks, betweenProbeTicks, ProbeRegistry.of(probes),
                autoCheckOnJoin, autoCheckDelayTicks, firstJoinOnly, maxConcurrentAutoChecks,
                timeoutTicks, 2);
    }

    public ClientDetectionConfig(boolean enabled, boolean doubleCheck, long timeoutTicks,
                                 long betweenProbeTicks, ProbeRegistry registry,
                                 boolean autoCheckOnJoin, long autoCheckDelayTicks,
                                 boolean firstJoinOnly, int maxConcurrentAutoChecks) {
        this(enabled, doubleCheck, timeoutTicks, betweenProbeTicks, registry,
                autoCheckOnJoin, autoCheckDelayTicks, firstJoinOnly, maxConcurrentAutoChecks,
                timeoutTicks, 2);
    }

    public ClientDetectionConfig {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        if (betweenProbeTicks < 0) throw new IllegalArgumentException("betweenProbeTicks cannot be negative");
        if (autoCheckDelayTicks < 0) throw new IllegalArgumentException("autoCheckDelayTicks cannot be negative");
        if (maxConcurrentAutoChecks < 1) throw new IllegalArgumentException("maxConcurrentAutoChecks must be positive");
        if (shortTimeoutTicks < 1) throw new IllegalArgumentException("shortTimeoutTicks must be positive");
        if (shortTimeoutAfterConsecutiveTimeouts < 1) {
            throw new IllegalArgumentException("shortTimeoutAfterConsecutiveTimeouts must be positive");
        }
        if (registry == null) throw new IllegalArgumentException("registry cannot be null");
    }

    public static ClientDetectionConfig disabled() {
        return new ClientDetectionConfig(false, true, 40, 1, List.of(), false, 1, false, List.of(), 32);
    }

    public static ClientDetectionConfig load(Path dataDirectory, Logger logger) {
        if (dataDirectory == null) return disabled();
        Path file = dataDirectory.resolve("client-detection.yml");
        if (!Files.isRegularFile(file)) return disabled();
        try {
            ClientDetectionConfig config = parse(Files.readString(file));
            config.warnings().forEach(logger::warning);
            return config;
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
        long shortTimeoutTicks = -1;
        int shortTimeoutAfterConsecutiveTimeouts = 2;
        long betweenProbeTicks = 0;
        boolean autoCheckOnJoin = false;
        long autoCheckDelayTicks = 1;
        boolean firstJoinOnly = false;
        int maxConcurrentAutoChecks = 32;
        boolean sawRoot = false;
        boolean inRoot = false;
        boolean inProbes = false;
        boolean inAutoCheck = false;
        boolean inAutoProbeIds = false;
        boolean legacyAutoProbeIdsConfigured = false;
        String currentId = null;
        Map<String, Map<String, String>> rawProbes = new LinkedHashMap<>();
        List<String> legacyAutoProbeIds = new java.util.ArrayList<>();

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            if (indent == 0) {
                inRoot = trimmed.equals("client-detection:");
                sawRoot |= inRoot;
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
                    case "short-timeout-ticks" -> shortTimeoutTicks = Long.parseLong(unquote(pair[1]));
                    case "short-timeout-after-consecutive-timeouts" ->
                            shortTimeoutAfterConsecutiveTimeouts = Integer.parseInt(unquote(pair[1]));
                    case "between-probe-ticks" -> betweenProbeTicks = Long.parseLong(unquote(pair[1]));
                    default -> { }
                }
                continue;
            }
            if (inAutoCheck) {
                if (indent == 4 && trimmed.equals("probes:")) {
                    inAutoProbeIds = true;
                    legacyAutoProbeIdsConfigured = true;
                    continue;
                }
                if (indent == 4 && trimmed.startsWith("- ")) {
                    legacyAutoProbeIds.add(unquote(trimmed.substring(2).trim()));
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
                    legacyAutoProbeIds.add(unquote(trimmed.substring(2).trim()));
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

        if (!sawRoot) {
            throw new IllegalArgumentException("file=client-detection.yml\npath=client-detection\n"
                    + "expected=object\nactual=missing");
        }

        List<ProbeDefinition> probes = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : rawProbes.entrySet()) {
            Map<String, String> values = entry.getValue();
            String path = "client-detection.probes." + entry.getKey();
            if (!entry.getKey().matches("[a-z0-9][a-z0-9._-]*")) {
                throw new IllegalArgumentException("file=client-detection.yml\npath=" + path
                        + "\nexpected=safe probe id\nactual=" + entry.getKey());
            }
            String displayName = unquote(required(values, "display-name", path + ".display-name"));
            String key = unquote(required(values, "key", path + ".key"));
            if (displayName.isBlank()) {
                throw new IllegalArgumentException("file=client-detection.yml\npath=" + path
                        + ".display-name\nexpected=non-empty value\nactual=blank");
            }
            if (key.isBlank()) {
                throw new IllegalArgumentException("file=client-detection.yml\npath=" + path
                        + ".key\nexpected=non-empty value\nactual=blank");
            }
            ProbeMode mode = enumValue(values, "mode", ProbeMode.class, path + ".mode");
            boolean probeEnabled = bool(values.getOrDefault("enabled", "true"),
                    path + ".enabled");
            boolean manual = bool(values.getOrDefault("manual", "true"), path + ".manual");
            boolean automatic = values.containsKey("automatic")
                    ? bool(values.get("automatic"), path + ".automatic")
                    : (!legacyAutoProbeIdsConfigured || legacyAutoProbeIds.contains(entry.getKey()));
            String fallback = unquote(values.getOrDefault("fallback", ""));
            ProbeVerificationStatus status = enumValue(values, "verification", ProbeVerificationStatus.class,
                    path + ".verification", "UNVERIFIED");
            String category = unquote(values.getOrDefault("category", "general"));
            String source = unquote(values.getOrDefault("source", ""));
            String notes = unquote(values.getOrDefault("notes", ""));
            if (status == ProbeVerificationStatus.VERIFIED && (notes.isBlank() || source.isBlank())) {
                throw new IllegalArgumentException("file=client-detection.yml\npath=" + path
                        + ".verification\nexpected=VERIFIED with non-blank notes and source\nactual=missing evidence metadata");
            }
            String expectedResponse = unquote(values.getOrDefault("expected-response", ""));
            probes.add(new ProbeDefinition(entry.getKey(), displayName, key, mode,
                    fallback, probeEnabled, manual, automatic, status, category, source, notes,
                    expectedResponse));
        }
        if (shortTimeoutTicks < 0) shortTimeoutTicks = timeoutTicks;
        return new ClientDetectionConfig(enabled, doubleCheck, timeoutTicks, betweenProbeTicks,
                ProbeRegistry.of(probes), autoCheckOnJoin, autoCheckDelayTicks, firstJoinOnly,
                maxConcurrentAutoChecks, shortTimeoutTicks, shortTimeoutAfterConsecutiveTimeouts);
    }

    private static String[] pair(String value) {
        String[] pair = value.split(":", 2);
        if (pair.length != 2) throw new IllegalArgumentException("Invalid config entry");
        return new String[]{pair[0].trim(), stripInlineComment(pair[1].trim())};
    }

    private static String required(Map<String, String> values, String key) {
        return required(values, key, key);
    }

    private static String required(Map<String, String> values, String key, String path) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("file=client-detection.yml\npath=" + path
                    + "\nexpected=non-empty value\nactual=missing");
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Map<String, String> values, String key,
                                                    Class<E> type, String path) {
        return enumValue(values, key, type, path, null);
    }

    private static <E extends Enum<E>> E enumValue(Map<String, String> values, String key,
                                                    Class<E> type, String path, String defaultValue) {
        String value = values.getOrDefault(key, defaultValue);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("file=client-detection.yml\npath="
                + path + "\nexpected=" + type.getSimpleName() + "\nactual=missing");
        try {
            return Enum.valueOf(type, unquote(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("file=client-detection.yml\npath=" + path
                    + "\nexpected=" + type.getSimpleName() + "\nactual=" + unquote(value), exception);
        }
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
        return registry.automatic();
    }

    /**
     * Deadline for the next probe batch given how many consecutive whole batches
     * in the current pass already timed out on the full deadline. The first
     * batches of a pass always use the full {@code timeoutTicks}; only a client
     * that stayed silent across {@code shortTimeoutAfterConsecutiveTimeouts}
     * whole windows is probed with the shorter deadline. Every probe still gets
     * its own sign editor interaction and a shortened wait is still recorded as
     * TIMEOUT, never as CLEAN, so result semantics are unchanged. Responsive
     * clients answer in well under a second and never reach the short deadline.
     * A {@code shortTimeoutTicks} greater than or equal to {@code timeoutTicks}
     * disables adaptation.
     */
    public long batchTimeoutTicks(int consecutiveTimeoutBatches) {
        if (consecutiveTimeoutBatches >= shortTimeoutAfterConsecutiveTimeouts
                && shortTimeoutTicks < timeoutTicks) {
            return shortTimeoutTicks;
        }
        return timeoutTicks;
    }

    public List<ProbeDefinition> probes() { return registry.all(); }
    public List<ProbeDefinition> manualProbes() { return registry.manual(); }
    public List<String> autoProbeIds() { return registry.automatic().stream().map(ProbeDefinition::id).toList(); }
    public ProbeRegistry probeRegistry() { return registry; }

    public List<String> warnings() {
        return registry.all().stream()
                .filter(probe -> !probe.manual() && !probe.automatic())
                .map(probe -> "client-detection.probes." + probe.id()
                        + " has manual=false and automatic=false; it will never run")
                .toList();
    }
}
