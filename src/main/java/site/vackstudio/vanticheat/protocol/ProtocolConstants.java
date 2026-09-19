package site.vackstudio.vanticheat.protocol;

public final class ProtocolConstants {

    public static final String PROTOCOL_CHANNEL = "vanticheat:verify";

    public static final int CURRENT_PROTOCOL_VERSION = 1;
    public static final int MIN_PROTOCOL_VERSION = 1;
    public static final int MAX_PROTOCOL_VERSION = 1;

    public static final int MAX_PAYLOAD_BYTES = 65535;
    public static final int MAX_SESSION_ID_LENGTH = 36;
    public static final int MAX_CHALLENGE_LENGTH = 44;
    public static final int MAX_REPORT_BYTES = 65535;
    public static final int MAX_MODS_PER_REPORT = 1000;
    public static final int MAX_STRING_LENGTH = 256;
    public static final int MAX_REASON_LENGTH = 256;
    public static final int MAX_ERROR_LENGTH = 256;

    private ProtocolConstants() {}

    public enum PacketType {
        REQUEST,
        CHALLENGE,
        RESPONSE,
        TERMINATE
    }

    public enum VerificationOutcome {
        SUCCESS,
        REJECTION,
        MALFORMED,
        PROTOCOL_MISMATCH,
        TIMEOUT
    }
}