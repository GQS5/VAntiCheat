package site.vackstudio.vanticheat.detection.probe;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable lookup and policy view of the configured probe definitions. */
public final class ProbeRegistry {
    private static final ProbeRegistry EMPTY = new ProbeRegistry(List.of());
    private final Map<String, ProbeDefinition> byId;
    private final List<ProbeDefinition> all;
    private final List<ProbeDefinition> enabled;
    private final List<ProbeDefinition> manual;
    private final List<ProbeDefinition> automatic;
    private final List<ProbeDefinition> verified;
    private final List<ProbeDefinition> unverified;
    private final List<ProbeDefinition> passive;
    private final List<ProbeDefinition> interactive;

    private ProbeRegistry(Collection<ProbeDefinition> definitions) {
        LinkedHashMap<String, ProbeDefinition> indexed = new LinkedHashMap<>();
        for (ProbeDefinition definition : definitions) {
            Objects.requireNonNull(definition, "probe definition");
            if (indexed.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate probe id: " + definition.id());
            }
        }
        byId = Map.copyOf(indexed);
        all = List.copyOf(indexed.values());
        enabled = all.stream().filter(ProbeDefinition::enabled).toList();
        manual = all.stream().filter(probe -> probe.enabled() && probe.manual()).toList();
        automatic = all.stream().filter(probe -> probe.enabled() && probe.automatic()).toList();
        verified = all.stream().filter(probe -> probe.verificationStatus() == ProbeVerificationStatus.VERIFIED).toList();
        unverified = all.stream().filter(probe -> probe.verificationStatus() != ProbeVerificationStatus.VERIFIED).toList();
        passive = all.stream().filter(probe -> probe.transport() == ProbeTransportMode.PASSIVE).toList();
        interactive = all.stream().filter(probe -> probe.transport() == ProbeTransportMode.INTERACTIVE).toList();
    }

    public static ProbeRegistry of(Collection<ProbeDefinition> definitions) {
        if (definitions.isEmpty()) return EMPTY;
        return new ProbeRegistry(List.copyOf(definitions));
    }

    public static ProbeRegistry empty() { return EMPTY; }

    public ProbeDefinition find(String id) {
        return id == null ? null : byId.get(id);
    }

    public ProbeDefinition get(String id) { return find(id); }
    public boolean contains(String id) { return id != null && byId.containsKey(id); }

    public ProbeDefinition require(String id) {
        ProbeDefinition definition = find(id);
        if (definition == null) throw new IllegalArgumentException("Unknown probe id: " + id);
        return definition;
    }

    public List<ProbeDefinition> all() { return all; }

    public List<ProbeDefinition> enabled() { return enabled; }

    public List<ProbeDefinition> manual() { return manual; }

    /** Probes configured for automatic use, before the transport capability gate. */
    public List<ProbeDefinition> automatic() { return automatic; }

    /**
     * Probes that may actually run as a background automatic check. By default only
     * {@link ProbeTransportMode#PASSIVE} probes qualify, so an automatic scan can never
     * open client UI and interrupt normal gameplay.
     */
    public List<ProbeDefinition> automaticEligible(boolean interactiveAutomaticEnabled) {
        return automatic.stream()
                .filter(probe -> probe.automaticEligible(interactiveAutomaticEnabled))
                .toList();
    }

    public List<ProbeDefinition> verified() { return verified; }

    public List<ProbeDefinition> unverified() { return unverified; }

    public List<ProbeDefinition> passive() { return passive; }

    public List<ProbeDefinition> interactive() { return interactive; }

    public List<ProbeDefinition> detectedCapable() {
        return all.stream().filter(ProbeDefinition::detectedCapable).toList();
    }

    public List<ProbeDefinition> detectedCapable(boolean interactiveAutomaticEnabled) {
        return automaticEligible(interactiveAutomaticEnabled).stream()
                .filter(ProbeDefinition::detectedCapable).toList();
    }

    public int size() { return all.size(); }
    public int enabledCount() { return enabled.size(); }
    public int manualCount() { return manual.size(); }
    public int automaticCount() { return automatic.size(); }
    public int automaticEligibleCount(boolean interactiveAutomaticEnabled) {
        return automaticEligible(interactiveAutomaticEnabled).size();
    }
    public int verifiedCount() { return verified.size(); }
    public int unverifiedCount() { return unverified.size(); }
    public int passiveCount() { return passive.size(); }
    public int interactiveCount() { return interactive.size(); }
    public int detectedCapableCount() { return detectedCapable().size(); }

    /** Probes that can reach DETECTED and are allowed to run automatically. */
    public int detectedCapableCount(boolean interactiveAutomaticEnabled) {
        return detectedCapable(interactiveAutomaticEnabled).size();
    }
}
