package site.vackstudio.vanticheat.enforcement;

public record EnforcementDecision(EnforcementAction action, String reason) {
    public EnforcementDecision {
        if (action == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Invalid enforcement decision");
        }
    }
}
