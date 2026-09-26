package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.detection.DetectionResult;

public record EnforcementOutcome(DetectionResult result, EnforcementDecision decision) { }
