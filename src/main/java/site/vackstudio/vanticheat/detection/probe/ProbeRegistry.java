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

    public List<ProbeDefinition> automatic() { return automatic; }

    public List<ProbeDefinition> verified() { return verified; }

    public List<ProbeDefinition> unverified() { return unverified; }

    public int size() { return all.size(); }
    public int enabledCount() { return enabled.size(); }
    public int manualCount() { return manual.size(); }
    public int automaticCount() { return automatic.size(); }
    public int verifiedCount() { return verified.size(); }
    public int unverifiedCount() { return unverified.size(); }
}
