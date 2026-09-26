package site.vackstudio.vanticheat.config;

public record EnforcementConfig(boolean enabled, String confirmedDetectionMessage) {
    public EnforcementConfig {
        if (confirmedDetectionMessage == null || confirmedDetectionMessage.isBlank()) {
            throw new IllegalArgumentException("Confirmed detection message cannot be blank");
        }
    }

    public static EnforcementConfig defaults() {
        return new EnforcementConfig(true, "Cheating detected.");
    }

    static EnforcementConfig parse(String content) {
        boolean enabled = true;
        String message = "Cheating detected.";
        boolean inEnforcement = false;
        boolean inConfirmed = false;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            if (indent == 0) {
                inEnforcement = trimmed.equals("enforcement:");
                inConfirmed = false;
                continue;
            }
            if (!inEnforcement) continue;
            if (indent == 2) {
                if (trimmed.equals("confirmed-detection:")) {
                    inConfirmed = true;
                    continue;
                }
                inConfirmed = false;
                String[] pair = pair(trimmed);
                if (pair[0].equals("enabled")) enabled = bool(pair[1]);
            } else if (indent >= 4 && inConfirmed) {
                String[] pair = pair(trimmed);
                if (pair[0].equals("message")) message = pair[1];
            }
        }
        return new EnforcementConfig(enabled, message);
    }

    private static String[] pair(String value) {
        String[] pair = value.split(":", 2);
        if (pair.length != 2) throw new IllegalArgumentException("Invalid enforcement config entry");
        return new String[]{pair[0].trim(), unquote(pair[1].trim())};
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
}
