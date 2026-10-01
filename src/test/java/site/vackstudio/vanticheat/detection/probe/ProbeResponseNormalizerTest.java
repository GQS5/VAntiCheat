package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.DetectionTarget;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProbeResponseNormalizerTest {
    private final ProbeRequest request = new ProbeRequest(UUID.randomUUID(),
            new DetectionTarget(UUID.randomUUID(), "player", true, new Object()),
            List.of(new ProbeDefinition("p", "Probe", "key.probe", ProbeMode.TRANSLATE,
                    "fallback", true, ProbeVerificationStatus.UNVERIFIED)));

    @Test
    void normalizesNullLinesFormattingWhitespaceAndUnicode() {
        ProbeResponse raw = new ProbeResponse(request.sessionId(), request.target().id(),
                Arrays.asList("  \u00a7aAppleSkin\u00a7r  ", null, "e\u0301", ""),
                ProbeResponse.Outcome.RESPONSE);

        ProbeResponse normalized = ProbeResponseNormalizer.normalize(request, raw);

        assertEquals(ProbeResponse.Outcome.RESPONSE, normalized.outcome());
        assertEquals(List.of("AppleSkin", "", "é", ""), normalized.lines());
    }

    @Test
    void preservesOptionalComponentIdentityAndRejectsMalformedLineCount() {
        ProbeResponse raw = new ProbeResponse(request.sessionId(), request.target().id(),
                List.of("Visible", "", "", "preventer"), ProbeResponse.Outcome.RESPONSE,
                Arrays.asList("translate:key.probe", null, null, "keybind:key.forward"));
        ProbeResponse normalized = ProbeResponseNormalizer.normalize(request, raw);
        assertEquals("translate:key.probe", normalized.componentIdentities().get(0));
        assertEquals("keybind:key.forward", normalized.componentIdentities().get(3));

        ProbeResponse malformed = new ProbeResponse(request.sessionId(), request.target().id(),
                List.of("only one line"), ProbeResponse.Outcome.RESPONSE);
        assertEquals(ProbeResponse.Outcome.ERROR,
                ProbeResponseNormalizer.normalize(request, malformed).outcome());
    }

    @Test
    void rejectsCrossSessionOrTargetResponses() {
        ProbeResponse wrongSession = new ProbeResponse(UUID.randomUUID(), request.target().id(),
                List.of("", "", "", ""), ProbeResponse.Outcome.RESPONSE);
        ProbeResponse wrongTarget = new ProbeResponse(request.sessionId(), UUID.randomUUID(),
                List.of("", "", "", ""), ProbeResponse.Outcome.RESPONSE);

        assertEquals(ProbeResponse.Outcome.ERROR,
                ProbeResponseNormalizer.normalize(request, wrongSession).outcome());
        assertEquals(ProbeResponse.Outcome.ERROR,
                ProbeResponseNormalizer.normalize(request, wrongTarget).outcome());
    }
}
