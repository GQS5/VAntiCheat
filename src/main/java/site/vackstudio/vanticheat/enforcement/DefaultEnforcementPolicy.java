package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.detection.DetectionResult;

import java.util.Objects;

public final class DefaultEnforcementPolicy implements EnforcementPolicy {
    private final boolean enabled;

    public DefaultEnforcementPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public EnforcementDecision decide(DetectionResult result) {
        Objects.requireNonNull(result, "result");
        if (!enabled) return new EnforcementDecision(EnforcementAction.NONE, "enforcement disabled");
        if (ConfirmedDetection.isConfirmed(result)) {
            return new EnforcementDecision(EnforcementAction.KICK, "confirmed detection");
        }
        return new EnforcementDecision(EnforcementAction.NONE, "result is not confirmed");
    }
}
