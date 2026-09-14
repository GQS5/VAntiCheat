package com.hexa.vanticheat.alerts;

import com.hexa.vanticheat.evidence.EvidenceRecord;
import com.hexa.vanticheat.evidence.Severity;

/** Fixed-format alert strings with severity (spec §25). */
public final class AlertFormatter {

    private AlertFormatter() {}

    public static String checkAlert(EvidenceRecord r) {
        Severity s = r.severity();
        return "[VAC] " + s.name() + " | " + r.playerName() + " | " + r.check()
                + " | Confidence " + r.confidence() + "% | VL " + String.format("%.1f", r.violation());
    }

    public static String confirmedClient(EvidenceRecord r) {
        return "[VAC] CRITICAL | " + r.playerName()
                + " | " + r.matchedIdentifier()
                + " | Confidence " + r.confidence() + "%"
                + " | " + r.values().getOrDefault("action", "KICK");
    }

    public static String generic(EvidenceRecord r, String action) {
        Severity s = r.severity();
        return "[VAC] " + s.name() + " | " + r.playerName()
                + " | " + r.detection()
                + " | Confidence " + r.confidence() + "% | " + action;
    }
}
