package com.hexa.vanticheat.checks;

import java.util.Map;

/** Result produced by a check; violation delta + evidence metadata. */
public record CheckResult(boolean failed, double vlAdd, int confidenceAdd,
                          String detection, String method, Map<String, String> values) {
    public static CheckResult pass() {
        return new CheckResult(false, 0, 0, "", "", Map.of());
    }

    public static CheckResult fail(double vl, int conf, String detection, String method, Map<String, String> values) {
        return new CheckResult(true, vl, conf, detection, method, values);
    }
}
