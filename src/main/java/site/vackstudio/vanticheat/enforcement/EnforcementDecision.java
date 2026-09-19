package site.vackstudio.vanticheat.enforcement;

public class EnforcementDecision {

    public enum Action {
        ALLOW,
        REJECT
    }

    public static final EnforcementDecision ALLOW = new EnforcementDecision(Action.ALLOW, "Verification passed");
    public static final EnforcementDecision REJECT = new EnforcementDecision(Action.REJECT, "Verification failed");
    public static final EnforcementDecision REJECT_UNKNOWN = new EnforcementDecision(Action.REJECT, "Session not found");
    public static final EnforcementDecision REJECT_BLOCKED = new EnforcementDecision(Action.REJECT, "Forbidden mod detected");
    public static final EnforcementDecision REJECT_TIMEOUT = new EnforcementDecision(Action.REJECT, "Verification timed out");
    public static final EnforcementDecision REJECT_INVALID = new EnforcementDecision(Action.REJECT, "Invalid verification data");

    private final Action action;
    private final String reason;

    public EnforcementDecision(Action action, String reason) {
        this.action = action;
        this.reason = reason;
    }

    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public boolean isAllowed() { return action == Action.ALLOW; }
    public boolean isRejected() { return action == Action.REJECT; }

    @Override
    public String toString() {
        return "EnforcementDecision{action=" + action + ", reason='" + reason + "'}";
    }
}