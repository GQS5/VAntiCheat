package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.detection.DetectionResult;

import java.util.Objects;

public final class DefaultEnforcementPolicy implements EnforcementPolicy {
    private final boolean enabled;
    private volatile boolean passiveAllowed;

    public DefaultEnforcementPolicy(boolean enabled) {
        this(enabled, false);
    }

    public DefaultEnforcementPolicy(boolean enabled, boolean passiveAllowed) {
        this.enabled = enabled;
        this.passiveAllowed = passiveAllowed;
    }

    public void setPassiveAllowed(boolean passiveAllowed) {
        this.passiveAllowed = passiveAllowed;
    }

    @Override
    public EnforcementDecision decide(DetectionResult result) {
        Objects.requireNonNull(result, "result");
        if (!enabled) return new EnforcementDecision(EnforcementAction.NONE, "enforcement disabled");
        if (ConfirmedDetection.isConfirmed(result, passiveAllowed)) {
            return new EnforcementDecision(EnforcementAction.KICK, "confirmed detection");
        }
        return new EnforcementDecision(EnforcementAction.NONE, "result is not confirmed");
    }
}
