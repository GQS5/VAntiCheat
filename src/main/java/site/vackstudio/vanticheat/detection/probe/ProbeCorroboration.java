package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit, evidence-aware promotion rules for a whole scan.
 *
 * <p>Detections are never added up numerically. A promotion happens only when the recorded
 * evidence itself is authoritative for the same target, which is why {@link Basis#EXACT_COMPONENT_IDENTITY}
 * and {@link Basis#EXACT_CONFIGURED_RESPONSE} promote on their own. Weak localized evidence
 * is reported with the reason it was not promoted instead of being rounded up into a
 * detection, because this protocol carries plain strings and cannot prove that two
 * ambiguous responses came from the same mod.
 */
public final class ProbeCorroboration {
    public enum Basis {
        /** Structured component identity equalled the configured key exactly. */
        EXACT_COMPONENT_IDENTITY,
        /** Exact configured expected response matched byte-for-byte after normalization. */
        EXACT_CONFIGURED_RESPONSE,
        /** The client resolved a translation key only the target's table defines. */
        IDENTITY_RESOLUTION,
        /** Ambiguous localized text with no identity attached. */
        WEAK_LOCALIZED,
        /** Nothing usable came back. */
        NO_EVIDENCE
    }

    public enum Conclusion {
        /** Authoritative evidence for at least one target. */
        CONFIRMED(DetectionStatus.DETECTED),
        /** Client answered but identity could not be established. */
        AMBIGUOUS(DetectionStatus.UNCERTAIN),
        /** Client answered and nothing indicated a probed target. */
        NO_TARGET(DetectionStatus.CLEAN),
        TIMED_OUT(DetectionStatus.TIMEOUT),
        UNSUPPORTED(DetectionStatus.UNSUPPORTED),
        /** Transport, scheduling, or processing failure. */
        FAILED(DetectionStatus.ERROR),
        /** Policy or eligibility stop. */
        NOT_RUN(DetectionStatus.SKIPPED),
        /** An answer arrived but the response channel was neutralized. */
        PROTECTED(DetectionStatus.PROTECTED);

        private final DetectionStatus status;

        Conclusion(DetectionStatus status) { this.status = status; }

        public DetectionStatus status() { return status; }
    }

    public record Observation(String probeId, DetectionStatus status, ProbeEvidenceStrength strength,
                              Basis basis, boolean sameTargetConfirmed) {
        public Observation {
            Objects.requireNonNull(probeId, "probeId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(strength, "strength");
            Objects.requireNonNull(basis, "basis");
        }

        public boolean authoritative() {
            return strength == ProbeEvidenceStrength.STRONG
                    && (basis == Basis.EXACT_COMPONENT_IDENTITY
                    || basis == Basis.EXACT_CONFIGURED_RESPONSE
                    || basis == Basis.IDENTITY_RESOLUTION);
        }
    }

    public record Decision(Conclusion conclusion, DetectionStatus status, List<String> promotedProbes,
                           String reason, int weakSignals, int authoritativeSignals) {
        public Decision {
            promotedProbes = List.copyOf(promotedProbes);
        }

        public boolean confirmed() { return conclusion == Conclusion.CONFIRMED; }
    }

    private ProbeCorroboration() { }

    public static Basis basisOf(ProbeEvaluation evaluation, ProbeDefinition probe,
                                boolean identityResolved) {
        if (evaluation.status() == DetectionStatus.TIMEOUT) return Basis.NO_EVIDENCE;
        if (evaluation.status() == DetectionStatus.UNSUPPORTED) return Basis.NO_EVIDENCE;
        if (evaluation.evidenceStrength() != ProbeEvidenceStrength.STRONG) {
            return evaluation.status() == DetectionStatus.UNCERTAIN ? Basis.WEAK_LOCALIZED : Basis.NO_EVIDENCE;
        }
        String detail = evaluation.detail();
        if (detail.startsWith("component identity exactly matches")) return Basis.EXACT_COMPONENT_IDENTITY;
        if (detail.startsWith("exact configured response matched")) return Basis.EXACT_CONFIGURED_RESPONSE;
        if (detail.startsWith("exact configured Meteor identifier returned")) return Basis.EXACT_CONFIGURED_RESPONSE;
        if (identityResolved) return Basis.IDENTITY_RESOLUTION;
        return Basis.EXACT_CONFIGURED_RESPONSE;
    }

    /**
     * Applies the promotion rules. Conflicting authoritative evidence for one probe, or a
     * protected channel, can never be promoted to a detection.
     */
    public static Decision decide(List<Observation> observations) {
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            return new Decision(Conclusion.NOT_RUN, DetectionStatus.SKIPPED, List.of(),
                    "no probe observations", 0, 0);
        }
        Set<String> promoted = new LinkedHashSet<>();
        int weak = 0;
        int authoritative = 0;
        Set<String> conflicting = new LinkedHashSet<>();
        for (Observation observation : observations) {
            if (observation.strength() == ProbeEvidenceStrength.WEAK) weak++;
            if (observation.authoritative()) authoritative++;
            if (observation.sameTargetConfirmed() && observation.authoritative()) {
                promoted.add(observation.probeId());
            }
            if (observation.status() == DetectionStatus.DETECTED && !observation.authoritative()) {
                // Defensive: a DETECTED without authoritative evidence is never promoted.
                conflicting.add(observation.probeId());
            }
        }
        promoted.removeAll(conflicting);

        if (!promoted.isEmpty()) {
            return new Decision(Conclusion.CONFIRMED, DetectionStatus.DETECTED, List.copyOf(promoted),
                    "authoritative exact identity for " + promoted.size() + " probe(s)", weak, authoritative);
        }
        if (observations.stream().anyMatch(item -> item.status() == DetectionStatus.PROTECTED)) {
            return new Decision(Conclusion.PROTECTED, DetectionStatus.PROTECTED, List.of(),
                    "client response channel was neutralized", weak, authoritative);
        }
        if (observations.stream().anyMatch(item -> item.status() == DetectionStatus.ERROR)) {
            return new Decision(Conclusion.FAILED, DetectionStatus.ERROR, List.of(),
                    "transport or processing failure", weak, authoritative);
        }
        if (observations.stream().anyMatch(item -> item.status() == DetectionStatus.TIMEOUT)) {
            return new Decision(Conclusion.TIMED_OUT, DetectionStatus.TIMEOUT, List.of(),
                    "client did not answer inside the deadline", weak, authoritative);
        }
        if (observations.stream().anyMatch(item -> item.status() == DetectionStatus.UNSUPPORTED)) {
            return new Decision(Conclusion.UNSUPPORTED, DetectionStatus.UNSUPPORTED, List.of(),
                    "transport cannot carry at least one probe", weak, authoritative);
        }
        if (weak > 0) {
            return new Decision(Conclusion.AMBIGUOUS, DetectionStatus.UNCERTAIN, List.of(),
                    weak + " ambiguous localized response(s) carry no identity and were not promoted",
                    weak, authoritative);
        }
        if (observations.stream().anyMatch(item -> item.status() == DetectionStatus.UNCERTAIN)) {
            return new Decision(Conclusion.AMBIGUOUS, DetectionStatus.UNCERTAIN, List.of(),
                    "client answered without a resolvable identity", weak, authoritative);
        }
        if (observations.stream().anyMatch(item -> item.status() == DetectionStatus.CLEAN)) {
            return new Decision(Conclusion.NO_TARGET, DetectionStatus.CLEAN, List.of(),
                    "client answered and no probed target was identified", weak, authoritative);
        }
        return new Decision(Conclusion.NOT_RUN, DetectionStatus.SKIPPED, List.of(),
                "all probes were skipped by policy", weak, authoritative);
    }

    public static List<Observation> observationsOf(List<ProbeDefinition> probes,
                                                   List<ProbeEvaluation> evaluations) {
        List<Observation> observations = new ArrayList<>();
        int size = Math.min(probes.size(), evaluations.size());
        for (int index = 0; index < size; index++) {
            ProbeDefinition probe = probes.get(index);
            ProbeEvaluation evaluation = evaluations.get(index);
            boolean identityResolved = evaluation.detail().startsWith("client resolved a translation key");
            observations.add(new Observation(probe.id(), evaluation.status(), evaluation.evidenceStrength(),
                    basisOf(evaluation, probe, identityResolved), true));
        }
        return observations;
    }
}
