package site.vackstudio.vanticheat.protocol;

public class ProtocolException extends Exception {
    public enum Type {
        MALFORMED,
        TIMEOUT,
        PROTOCOL_MISMATCH,
        SESSION_INVALID,
        CHALLENGE_MISMATCH,
        REPLAY_DETECTED,
        OVERSIZED,
        DUPLICATE,
        UNKNOWN
    }

    private final Type type;

    public ProtocolException(Type type, String message) {
        super(message);
        this.type = type;
    }

    public ProtocolException(Type type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public Type getType() { return type; }
}