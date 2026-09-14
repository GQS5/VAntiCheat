package com.hexa.vanticheat.client;

import java.util.List;
import java.util.Map;

/**
 * Known cheat-client fingerprints.
 *
 * <p>HONEST SCOPE: a plain Paper/Folia plugin can only see what the client
 * volunteers: the {@code minecraft:brand} string and registered plugin
 * channels. Most ghost clients (Vape, Raven) send vanilla/fabric brands and
 * NO identifiable channel, so they are NOT detectable server-side. Entries
 * below marked {@code detectable=false} are documented for correlation only
 * (e.g. combined with behavior) and never auto-punished alone.
 */
public final class KnownClients {

    public record Fingerprint(String id, String display, List<String> brandSubstrings,
                              List<String> channelSubstrings, int weight, boolean detectable) {}

    public static final List<Fingerprint> ALL = List.of(
            new Fingerprint("meteor", "Meteor", List.of("meteor"), List.of("meteor"), 70, true),
            new Fingerprint("wurst", "Wurst", List.of("wurst"), List.of("wurst"), 70, true),
            new Fingerprint("liquidbounce", "LiquidBounce", List.of("liquidbounce", "liquidBounce"), List.of("liquidbounce", "liquidbounce"), 70, true),
            new Fingerprint("impact", "Impact", List.of("impact"), List.of("impact"), 70, true),
            new Fingerprint("aristois", "Aristois", List.of("aristois", "emm"), List.of("aristois"), 70, true),
            new Fingerprint("bleachhack", "BleachHack", List.of("bleachhack", "bleach"), List.of("bleachhack"), 60, true),
            new Fingerprint("inertia", "Inertia", List.of("inertia"), List.of("inertia"), 60, true),
            new Fingerprint("future", "Future", List.of("future"), List.of("future"), 60, true),
            new Fingerprint("rise", "Rise", List.of("rise"), List.of("rise"), 60, true),
            new Fingerprint("sigma", "Sigma", List.of("sigma"), List.of("sigma"), 60, true),
            new Fingerprint("doomsday", "Doomsday", List.of("doomsday"), List.of("doomsday"), 60, true),
            new Fingerprint("augustus", "Augustus", List.of("augustus"), List.of("augustus"), 60, true),
            // Ghost clients: undetectable via brand/channel alone. Kept for behavior-correlation, never sole proof.
            new Fingerprint("vape", "Vape", List.of(), List.of(), 0, false),
            new Fingerprint("raven", "Raven", List.of(), List.of(), 0, false)
    );

    public static final Map<String, Integer> DEFAULT_WEIGHTS = Map.ofEntries(
            Map.entry("meteor", 70), Map.entry("wurst", 70), Map.entry("liquidbounce", 70),
            Map.entry("impact", 70), Map.entry("aristois", 70), Map.entry("bleachhack", 60),
            Map.entry("inertia", 60), Map.entry("future", 60), Map.entry("rise", 60),
            Map.entry("sigma", 60), Map.entry("doomsday", 60), Map.entry("augustus", 60)
    );

    private KnownClients() {}
}
