package site.vackstudio.vanticheat.enforcement;

public interface EnforcementExecutor {
    boolean kick(EnforcementTarget target, String message);
}
