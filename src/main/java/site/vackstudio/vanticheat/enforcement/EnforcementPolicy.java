package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.detection.DetectionResult;

public interface EnforcementPolicy {
    EnforcementDecision decide(DetectionResult result);
}
