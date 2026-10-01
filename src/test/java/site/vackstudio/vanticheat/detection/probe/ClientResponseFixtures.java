package site.vackstudio.vanticheat.detection.probe;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic client-response fixtures for the sign round-trip.
 *
 * <p>These reproduce the response representations recorded against real clients during live
 * validation, expressed only in the evidence structures this project already uses: four
 * plain sign lines plus the optional component-identity string the client echoes back. They
 * are test inputs, not new live-verification claims, and they deliberately include the
 * failure shapes (ambiguous localization, silence, neutralized key) that the pipeline must
 * report honestly instead of promoting.
 */
public final class ClientResponseFixtures {
    /** Line 3 always carries the exploit-preventer keybind the transport plants. */
    public static final String EXPLOIT_PREVENTER_LINE = CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY;
    private static final int SIGN_LINES = ProbeResponseNormalizer.SIGN_LINE_COUNT;

    private ClientResponseFixtures() { }

    /** A rendered client answer for one batch: one line per probe, plus the preventer line. */
    public record Rendered(List<String> lines, List<String> identities) {
        public Rendered {
            // Identities are legitimately absent (null) for most responses, so these lists
            // are copied defensively rather than through List.copyOf.
            lines = java.util.Collections.unmodifiableList(new ArrayList<>(lines));
            identities = java.util.Collections.unmodifiableList(new ArrayList<>(identities));
        }
    }

    /** Clean vanilla and clean Fabric both echo the sentinel for every key. */
    public static Rendered clean(List<ProbeDefinition> batch) {
        List<String> lines = new ArrayList<>();
        List<String> identities = new ArrayList<>();
        for (ProbeDefinition probe : batch) {
            lines.add(probe.fallback());
            identities.add(null);
        }
        return finish(lines, identities);
    }

    /** Meteor 1.21.11-86 resolving its open-GUI key, as recorded live. */
    public static Rendered meteor(ProbeDefinition meteorProbe) {
        return single(meteorProbe, "Open GUI", null);
    }

    /** AppleSkin 3.0.8 resolving its title key, as recorded live. */
    public static Rendered appleSkin(ProbeDefinition appleSkinProbe) {
        return single(appleSkinProbe, "AppleSkin", null);
    }

    /** Jade 21.1.6 resolving its configuration key, as recorded live. */
    public static Rendered jadeConfig(ProbeDefinition jadeProbe) {
        return single(jadeProbe, "Jade Configuration", null);
    }

    /**
     * The same target key resolved under a different client locale. This is the case a
     * recorded en_us string cannot see, and the reason identity resolution exists.
     */
    public static Rendered targetOtherLocale(ProbeDefinition probe, String renderedText) {
        return single(probe, renderedText, null);
    }

    /** A client that renders translated text but exposes no component identity. */
    public static Rendered ambiguousLocalized(ProbeDefinition probe, String renderedText) {
        return single(probe, renderedText, null);
    }

    /** An exploit preventer that echoes the raw key back instead of rendering it. */
    public static Rendered keyEchoed(ProbeDefinition probe) {
        return single(probe, probe.key(), null);
    }

    /** A client holding a different mod's key: never this target. */
    public static Rendered otherTargetKey(ProbeDefinition probe, String otherKey) {
        return single(probe, otherKey, "translate:" + otherKey);
    }

    /** A keybind client that exposes its structured keybind identity. */
    public static Rendered keybindIdentity(ProbeDefinition probe, String keybindKey) {
        return single(probe, "Right Shift", "keybind:" + keybindKey);
    }

    private static Rendered single(ProbeDefinition probe, String renderedText, String identity) {
        List<String> lines = new ArrayList<>();
        List<String> identities = new ArrayList<>();
        lines.add(renderedText);
        identities.add(identity);
        return finish(lines, identities);
    }

    private static Rendered finish(List<String> lines, List<String> identities) {
        while (lines.size() < SIGN_LINES - 1) {
            lines.add("");
            identities.add(null);
        }
        lines.add(EXPLOIT_PREVENTER_LINE);
        identities.add(null);
        return new Rendered(lines, identities);
    }

    /** Binds a rendered answer to a real request so normalization accepts it. */
    public static ProbeResponse bind(ProbeRequest request, Rendered rendered) {
        return new ProbeResponse(request.sessionId(), request.target().id(),
                rendered.lines(), ProbeResponse.Outcome.RESPONSE, rendered.identities());
    }

    public static ProbeResponse bind(ProbeRequest request, Rendered rendered, ProbeResponse.Outcome outcome) {
        return new ProbeResponse(request.sessionId(), request.target().id(),
                rendered.lines(), outcome, rendered.identities());
    }

    public static ProbeResponse silent(ProbeRequest request) {
        return ProbeResponse.timeout(request);
    }
}
