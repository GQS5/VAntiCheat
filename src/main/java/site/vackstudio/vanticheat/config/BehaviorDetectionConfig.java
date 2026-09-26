package site.vackstudio.vanticheat.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public record BehaviorDetectionConfig(boolean enabled, boolean reachEnabled, String reachMode,
                                       boolean combatEnabled, boolean killauraEnabled, String killauraMode,
                                       boolean autoclickerEnabled, String autoclickerMode,
                                       boolean movementEnabled, boolean flyEnabled, String flyMode,
                                      boolean noFallEnabled, String noFallMode,
                                      boolean speedEnabled, String speedMode,
                                      boolean scaffoldEnabled, String scaffoldMode) {
    public BehaviorDetectionConfig(boolean enabled, boolean reachEnabled, String reachMode) {
        this(enabled, reachEnabled, reachMode, true, true, "observe", true, "observe", true, true, "observe", true, "observe", true, "observe", true, "observe");
    }

    public BehaviorDetectionConfig(boolean enabled, boolean reachEnabled, String reachMode,
                                   boolean combatEnabled, boolean killauraEnabled, String killauraMode) {
        this(enabled, reachEnabled, reachMode, combatEnabled, killauraEnabled, killauraMode,
                true, "observe", true, true, "observe", true, "observe", true, "observe", true, "observe");
    }

    public BehaviorDetectionConfig {
        if (reachMode == null || reachMode.isBlank()) throw new IllegalArgumentException("reachMode cannot be blank");
        if (!reachMode.equalsIgnoreCase("observe")) {
            throw new IllegalArgumentException("Only reach mode=observe is supported");
        }
        if (killauraMode == null || !killauraMode.equalsIgnoreCase("observe")) {
            throw new IllegalArgumentException("Only killaura mode=observe is supported");
        }
        if (autoclickerMode == null || !autoclickerMode.equalsIgnoreCase("observe")) {
            throw new IllegalArgumentException("Only autoclicker mode=observe is supported");
        }
        if (flyMode == null || !flyMode.equalsIgnoreCase("observe")) {
            throw new IllegalArgumentException("Only fly mode=observe is supported");
        }
        if (noFallMode == null || !noFallMode.equalsIgnoreCase("observe")) {
            throw new IllegalArgumentException("Only no-fall mode=observe is supported");
        }
        if (speedMode == null || !speedMode.equalsIgnoreCase("observe")) {
            throw new IllegalArgumentException("Only speed mode=observe is supported");
        }
        if (scaffoldMode == null || !scaffoldMode.equalsIgnoreCase("observe")) {
            throw new IllegalArgumentException("Only scaffold mode=observe is supported");
        }
    }

    public static BehaviorDetectionConfig defaults() {
        return new BehaviorDetectionConfig(true, true, "observe", true, true, "observe", true, "observe", true, true, "observe", true, "observe", true, "observe", true, "observe");
    }

    public static BehaviorDetectionConfig load(Path dataDirectory, Logger logger) {
        if (dataDirectory == null) return defaults();
        Path file = dataDirectory.resolve("behavior-detection.yml");
        if (!Files.isRegularFile(file)) return defaults();
        try {
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Unable to load behavior-detection.yml; behavior detection disabled", exception);
            return new BehaviorDetectionConfig(false, false, "observe", false, false, "observe", false, "observe", false, false, "observe", false, "observe", false, "observe", false, "observe");
        }
    }

    static BehaviorDetectionConfig parse(String content) {
        boolean enabled = true;
        boolean reachEnabled = true;
        String reachMode = "observe";
        boolean combatEnabled = true;
        boolean killauraEnabled = true;
        String killauraMode = "observe";
        boolean autoclickerEnabled = true;
        String autoclickerMode = "observe";
        boolean movementEnabled = true;
        boolean flyEnabled = true;
        String flyMode = "observe";
        boolean noFallEnabled = true;
        String noFallMode = "observe";
        boolean speedEnabled = true;
        String speedMode = "observe";
        boolean scaffoldEnabled = true;
        String scaffoldMode = "observe";
        boolean inRoot = false;
        boolean inReach = false;
        boolean inCombat = false;
        boolean inKillaura = false;
        boolean inAutoclicker = false;
        boolean inMovement = false;
        boolean inFly = false;
        boolean inNoFall = false;
        boolean inSpeed = false;
        boolean inScaffold = false;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            if (indent == 0) {
                inRoot = trimmed.equals("behavior-detection:");
                inReach = false;
                inCombat = false;
                inKillaura = false;
                inAutoclicker = false;
                inMovement = false;
                inFly = false;
                inNoFall = false;
                inSpeed = false;
                inScaffold = false;
                continue;
            }
            if (!inRoot) continue;
            if (indent == 2) {
                if (trimmed.equals("reach:")) {
                    inReach = true;
                    inCombat = false;
                    inKillaura = false;
                    inAutoclicker = false;
                    continue;
                }
                if (trimmed.equals("combat:")) {
                    inReach = false;
                    inCombat = true;
                    inKillaura = false;
                    inAutoclicker = false;
                    continue;
                }
                if (trimmed.equals("movement:")) {
                    inReach = false;
                    inCombat = false;
                    inKillaura = false;
                    inMovement = true;
                    inFly = false;
                    inNoFall = false;
                    inSpeed = false;
                    inScaffold = false;
                    continue;
                }
                String[] pair = pair(trimmed);
                if (pair[0].equals("enabled")) {
                    if (inCombat) combatEnabled = bool(pair[1]);
                    else if (inMovement) movementEnabled = bool(pair[1]);
                    else {
                        enabled = bool(pair[1]);
                        inReach = false;
                        inCombat = false;
                        inKillaura = false;
                        inAutoclicker = false;
                        inMovement = false;
                        inFly = false;
                        inNoFall = false;
                        inSpeed = false;
                        inScaffold = false;
                    }
                } else {
                    inReach = false;
                    inCombat = false;
                    inKillaura = false;
                    inAutoclicker = false;
                    inMovement = false;
                    inFly = false;
                    inNoFall = false;
                    inSpeed = false;
                    inScaffold = false;
                }
                continue;
            }
            if (inReach && indent >= 4) {
                String[] pair = pair(trimmed);
                switch (pair[0]) {
                    case "enabled" -> reachEnabled = bool(pair[1]);
                    case "mode" -> reachMode = pair[1];
                    default -> { }
                }
            } else if (inCombat && indent == 4 && trimmed.equals("killaura:")) {
                inKillaura = true;
            } else if (inCombat && inKillaura && indent >= 6) {
                String[] pair = pair(trimmed);
                switch (pair[0]) {
                    case "enabled" -> killauraEnabled = bool(pair[1]);
                    case "mode" -> killauraMode = pair[1];
                    default -> { }
                }
            } else if (inCombat && indent == 4 && trimmed.equals("autoclicker:")) {
                inKillaura = false;
                inAutoclicker = true;
            } else if (inCombat && inAutoclicker && indent >= 6) {
                String[] pair = pair(trimmed);
                switch (pair[0]) {
                    case "enabled" -> autoclickerEnabled = bool(pair[1]);
                    case "mode" -> autoclickerMode = pair[1];
                    default -> { }
                }
            } else if (inMovement && indent == 4 && trimmed.equals("fly:")) {
                inFly = true;
                inNoFall = false;
                inSpeed = false;
            } else if (inMovement && inFly && indent >= 6) {
                String[] pair = pair(trimmed);
                switch (pair[0]) {
                    case "enabled" -> flyEnabled = bool(pair[1]);
                    case "mode" -> flyMode = pair[1];
                    default -> { }
                }
            } else if (inMovement && indent == 4 && trimmed.equals("no-fall:")) {
                inFly = false;
                inNoFall = true;
                inSpeed = false;
            } else if (inMovement && inNoFall && indent >= 6) {
                String[] pair = pair(trimmed);
                switch (pair[0]) {
                    case "enabled" -> noFallEnabled = bool(pair[1]);
                    case "mode" -> noFallMode = pair[1];
                    default -> { }
                }
            } else if (inMovement && indent == 4 && trimmed.equals("speed:")) {
                inFly = false;
                inNoFall = false;
                inSpeed = true;
            } else if (inMovement && inSpeed && indent >= 6) {
                String[] pair = pair(trimmed);
                switch (pair[0]) {
                    case "enabled" -> speedEnabled = bool(pair[1]);
                    case "mode" -> speedMode = pair[1];
                    default -> { }
                }
            } else if (inMovement && indent == 4 && trimmed.equals("scaffold:")) {
                inFly = false;
                inNoFall = false;
                inSpeed = false;
                inScaffold = true;
            } else if (inMovement && inScaffold && indent >= 6) {
                String[] pair = pair(trimmed);
                switch (pair[0]) {
                    case "enabled" -> scaffoldEnabled = bool(pair[1]);
                    case "mode" -> scaffoldMode = pair[1];
                    default -> { }
                }
            }
        }
        return new BehaviorDetectionConfig(enabled, reachEnabled, reachMode, combatEnabled,
                killauraEnabled, killauraMode, autoclickerEnabled, autoclickerMode,
                movementEnabled, flyEnabled, flyMode,
                noFallEnabled, noFallMode, speedEnabled, speedMode, scaffoldEnabled, scaffoldMode);
    }

    private static String[] pair(String value) {
        String[] pair = value.split(":", 2);
        if (pair.length != 2) throw new IllegalArgumentException("Invalid behavior config entry");
        return new String[]{pair[0].trim(), pair[1].trim()};
    }

    private static boolean bool(String value) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Expected boolean");
    }
}
