package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.detection.Evidence;
import site.vackstudio.vanticheat.detection.EvidenceType;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class EnforcementService {
    private final EnforcementPolicy policy;
    private final EnforcementExecutor executor;
    private final String kickMessage;
    private final Logger logger;
    private final TrustedPlayerService trustedPlayers;
    private final Set<String> handled = ConcurrentHashMap.newKeySet();

    public EnforcementService(EnforcementPolicy policy, EnforcementExecutor executor,
                               String kickMessage, Logger logger) {
        this(policy, executor, kickMessage, logger, TrustedPlayerService.NONE);
    }

    public EnforcementService(EnforcementPolicy policy, EnforcementExecutor executor,
                               String kickMessage, Logger logger, TrustedPlayerService trustedPlayers) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.kickMessage = Objects.requireNonNull(kickMessage, "kickMessage");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.trustedPlayers = Objects.requireNonNull(trustedPlayers, "trustedPlayers");
    }

    public EnforcementOutcome enforce(EnforcementTarget target, DetectionResult result) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
        EnforcementDecision decision = policy.decide(result);
        EnforcementAction action = decision.action();
        String reason = decision.reason();

        if (trustedPlayers.isTrusted(target.id())) {
            action = EnforcementAction.NONE;
            reason = "trusted player bypass";
        } else {
            String enforcementKey = key(target, result);
            if (action == EnforcementAction.KICK && !handled.add(enforcementKey)) {
                action = EnforcementAction.NONE;
                reason = "enforcement already applied";
            } else if (action == EnforcementAction.KICK) {
                if (!target.online() || !executor.kick(target, kickMessage(result))) {
                    action = EnforcementAction.NONE;
                    reason = "target offline or kick unavailable";
                }
            }
        }

        if (ConfirmedDetection.isConfirmed(result)) {
            logger.info("Confirmed detection: " + target.name()
                    + " - Action: " + action
                    + " - Mods: " + detectedMods(result)
                    + " - Reason: " + result.reason());
        }
        Map<String, String> metadata = new HashMap<>();
        metadata.put("action", action.name());
        metadata.put("reason", reason);
        result.evidence().stream()
                .filter(item -> "CONFIRMATION".equals(item.metadata().get("pass")))
                .map(item -> item.metadata().get("probe"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .ifPresent(probe -> metadata.put("probe", probe));
        Evidence evidence = new Evidence(EvidenceType.SYSTEM, "enforcement", Instant.now(), metadata);
        return new EnforcementOutcome(result.withEvidence(evidence),
                new EnforcementDecision(action, reason));
    }

    private static String key(EnforcementTarget target, DetectionResult result) {
        String session = result.evidence().stream()
                .map(item -> item.metadata().get("session"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        return target.id() + ":" + (session == null ? result.hashCode() : session);
    }

    private String kickMessage(DetectionResult result) {
        return kickMessage + "\nDetected: " + detectedMods(result)
                + "\nReason: " + result.reason();
    }

    private static String detectedMods(DetectionResult result) {
        var confirmed = probeDetails(result, "CONFIRMATION");
        if (!confirmed.isEmpty()) return String.join(", ", confirmed);
        var initial = probeDetails(result, "INITIAL");
        return initial.isEmpty() ? "unknown" : String.join(", ", initial);
    }

    private static java.util.List<String> probeDetails(DetectionResult result, String pass) {
        return result.evidence().stream()
                .filter(item -> pass.equals(item.metadata().get("pass")))
                .filter(item -> "DETECTED".equals(item.metadata().get("classification")))
                .map(item -> {
                    String displayName = item.metadata().get("displayName");
                    String probe = item.metadata().get("probe");
                    return displayName == null || displayName.isBlank() ? probe : displayName;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new),
                        java.util.List::copyOf));
    }
}
