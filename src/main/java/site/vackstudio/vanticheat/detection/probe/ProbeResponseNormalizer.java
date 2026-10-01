package site.vackstudio.vanticheat.detection.probe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

/** Pure, bounded normalization for the four-line sign response protocol. */
public final class ProbeResponseNormalizer {
    public static final int SIGN_LINE_COUNT = 4;

    private ProbeResponseNormalizer() { }

    public static ProbeResponse normalize(ProbeRequest request, ProbeResponse response) {
        if (response == null) return ProbeResponse.error(request);
        if (!request.sessionId().equals(response.sessionId())
                || !request.target().id().equals(response.targetId())) {
            return ProbeResponse.error(request);
        }
        if (response.outcome() != ProbeResponse.Outcome.RESPONSE) return response;
        if (response.lines().size() != SIGN_LINE_COUNT) return ProbeResponse.error(request);
        ArrayList<String> lines = new ArrayList<>(SIGN_LINE_COUNT);
        ArrayList<String> identities = new ArrayList<>(SIGN_LINE_COUNT);
        for (int index = 0; index < SIGN_LINE_COUNT; index++) {
            lines.add(normalizeText(response.lines().get(index)));
            identities.add(normalizeIdentity(response.componentIdentities().get(index)));
        }
        return new ProbeResponse(request.sessionId(), request.target().id(), lines,
                ProbeResponse.Outcome.RESPONSE, identities);
    }

    public static String normalizeText(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder plain = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\u00a7' && index + 1 < value.length()
                    && isFormattingCode(value.charAt(index + 1))) {
                index++;
                continue;
            }
            plain.append(character);
        }
        return Normalizer.normalize(plain, Normalizer.Form.NFC).strip();
    }

    private static String normalizeIdentity(String identity) {
        if (identity == null || identity.isBlank()) return null;
        String value = identity.strip();
        int delimiter = value.indexOf(':');
        if (delimiter < 1 || delimiter == value.length() - 1) return null;
        String type = value.substring(0, delimiter).toLowerCase(Locale.ROOT);
        if (!type.equals("translate") && !type.equals("keybind")) return null;
        return type + ":" + value.substring(delimiter + 1).strip();
    }

    private static boolean isFormattingCode(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
                || (code >= 'k' && code <= 'o') || code == 'r' || code == 'x'
                || (code >= 'A' && code <= 'F') || (code >= 'K' && code <= 'O')
                || code == 'R' || code == 'X';
    }
}
