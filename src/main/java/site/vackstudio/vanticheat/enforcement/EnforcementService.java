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
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

public final class EnforcementService {
    /** Test-visible bound for the completed-key cache. */
    public static final int MAX_RETAINED_ENFORCEMENT_KEYS = 4096;
    static final long ENFORCEMENT_KEY_TTL_NANOS = TimeUnit.MINUTES.toNanos(15);
    private final EnforcementPolicy policy;
    private final EnforcementExecutor executor;
    private final String kickMessage;
    private final Logger logger;
    private final TrustedPlayerService trustedPlayers;
    private final LongSupplier nanoTime;
    private final Object enforcementGate = new Object();
    private final Set<String> inFlight = new HashSet<>();
    private final LinkedHashMap<String, Long> recentlyHandled = new LinkedHashMap<>();
    private enum Claim { CLAIMED, DUPLICATE, CAPACITY }

    public EnforcementService(EnforcementPolicy policy, EnforcementExecutor executor,
                               String kickMessage, Logger logger) {
        this(policy, executor, kickMessage, logger, TrustedPlayerService.NONE);
    }

    public EnforcementService(EnforcementPolicy policy, EnforcementExecutor executor,
                               String kickMessage, Logger logger, TrustedPlayerService trustedPlayers) {
        this(policy, executor, kickMessage, logger, trustedPlayers, System::nanoTime);
    }

    EnforcementService(EnforcementPolicy policy, EnforcementExecutor executor, String kickMessage,
                       Logger logger, TrustedPlayerService trustedPlayers, LongSupplier nanoTime) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.kickMessage = Objects.requireNonNull(kickMessage, "kickMessage");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.trustedPlayers = Objects.requireNonNull(trustedPlayers, "trustedPlayers");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
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
            Claim claim = action == EnforcementAction.KICK ? claimEnforcement(enforcementKey) : Claim.CLAIMED;
            if (action == EnforcementAction.KICK && claim != Claim.CLAIMED) {
                action = EnforcementAction.NONE;
                reason = claim == Claim.DUPLICATE ? "enforcement already applied"
                        : "enforcement deduplication capacity reached";
            } else if (action == EnforcementAction.KICK) {
                try {
                    if (!target.online() || !executor.kick(target, kickMessage(result))) {
                        action = EnforcementAction.NONE;
                        reason = "target offline or kick unavailable";
                    }
                } finally {
                    completeEnforcement(enforcementKey);
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

    public boolean isTrusted(UUID playerId) {
        return trustedPlayers.isTrusted(Objects.requireNonNull(playerId, "playerId"));
    }

    private Claim claimEnforcement(String key) {
        synchronized (enforcementGate) {
            expireOldKeys(nanoTime.getAsLong());
            if (inFlight.contains(key) || recentlyHandled.containsKey(key)) return Claim.DUPLICATE;
            if (inFlight.size() + recentlyHandled.size() >= MAX_RETAINED_ENFORCEMENT_KEYS) {
                return Claim.CAPACITY;
            }
            inFlight.add(key);
            return Claim.CLAIMED;
        }
    }

    private void completeEnforcement(String key) {
        synchronized (enforcementGate) {
            inFlight.remove(key);
            long now = nanoTime.getAsLong();
            recentlyHandled.put(key, now);
            expireOldKeys(now);
        }
    }

    private void expireOldKeys(long now) {
        var iterator = recentlyHandled.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() < ENFORCEMENT_KEY_TTL_NANOS) break;
            iterator.remove();
        }
    }

    /** Test-visible count of retained completed enforcement keys. */
    public int retainedEnforcementKeyCount() {
        synchronized (enforcementGate) { return recentlyHandled.size(); }
    }

    /** Test-visible count of in-flight enforcement reservations. */
    public int inFlightEnforcementCount() {
        synchronized (enforcementGate) { return inFlight.size(); }
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
        String mods = detectedMods(result);
        String reason = result.reason();
        if (kickMessage.contains("%mods%") || kickMessage.contains("%reason%")) {
            return kickMessage.replace("%mods%", mods).replace("%reason%", reason);
        }
        return kickMessage + "\nDetected: " + mods + "\nReason: " + reason;
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
