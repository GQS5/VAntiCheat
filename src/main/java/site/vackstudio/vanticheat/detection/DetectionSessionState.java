package site.vackstudio.vanticheat.detection;

public enum DetectionSessionState {
    NEW,
    STARTING,
    RUNNING,
    COMPLETING,
    COMPLETED,
    CANCELLED,
    TIMED_OUT,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == TIMED_OUT || this == FAILED;
    }
}
