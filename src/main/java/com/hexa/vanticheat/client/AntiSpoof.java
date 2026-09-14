package com.hexa.vanticheat.client;

import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;

/**
 * AntiSpoof consistency matrix (spec §8):
 * brand × channels × fingerprint × behavior → SPOOF SUSPECTED or NO ACTION.
 * Unknown/unknown/unknown → NO ACTION. Never punishes unusual-but-unknown.
 */
public final class AntiSpoof {

    public enum Verdict { NO_ACTION, SPOOF_SUSPECTED, CONFIRMED_SPOOF }

    private final VConfig config;
    private final EvidenceManager evidence;
    private final ConfidenceEngine confidence;

    public AntiSpoof(VConfig config, EvidenceManager evidence, ConfidenceEngine confidence) {
        this.config = config;
        this.evidence = evidence;
        this.confidence = confidence;
    }

    public Verdict evaluate(String brand, java.util.Set<String> channels) {
        boolean knownBrand = ClientFingerprint.matchBrand(brand).isPresent() || ClientFingerprint.looksVanilla(brand);
        boolean cheatChannel = false;
        for (String ch : channels) {
            if (ClientFingerprint.matchChannel(ch).isPresent()) { cheatChannel = true; break; }
        }
        boolean claimsVanilla = ClientFingerprint.looksVanilla(brand);
        if (claimsVanilla && cheatChannel) return Verdict.SPOOF_SUSPECTED;
        if (!knownBrand && !cheatChannel) return Verdict.NO_ACTION; // unknown/unknown → nothing
        return Verdict.NO_ACTION;
    }

    public void onUpdate(Player player, ClientProfile prof) {
        if (!config.getBoolean("antispoof.enabled", true)) return;
        String brand = prof.brand();
        if (brand == null || brand.equals("unknown")) return;
        Verdict v = evaluate(brand, prof.channels());
        if (v == Verdict.NO_ACTION) {
            // Weak note: vanilla brand + mod channel is not spoof (legit combos exist).
            for (String ch : prof.channels()) {
                if (isModChannel(ch) && ClientFingerprint.looksVanilla(brand)) {
                    int conf = confidence.addSignal(player.getUniqueId(), "spoof_inconsistency", 5);
                    evidence.report(EvidenceRecord.builder()
                            .player(player.getUniqueId(), player.getName())
                            .detector("AntiSpoof").check("AntiSpoof").detection("Note: vanilla brand with mod channel " + ch)
                            .detectionMethod("Consistency").confidence(conf).violation(0)
                            .brand(brand).matched(ch)
                            .values(Map.of("channel", ch, "verdict", "NO_ACTION"))
                            .build());
                    return;
                }
            }
            return;
        }
        // SPOOF_SUSPECTED: strong cheat-channel contradicting vanilla claim.
        for (String ch : prof.channels()) {
            Optional<KnownClients.Fingerprint> fp = ClientFingerprint.matchChannel(ch);
            if (fp.isPresent()) {
                int conf = confidence.addSignal(player.getUniqueId(), "spoof_inconsistency", 20);
                evidence.report(EvidenceRecord.builder()
                        .player(player.getUniqueId(), player.getName())
                        .detector("AntiSpoof").check("AntiSpoof")
                        .detection("SPOOF SUSPECTED: claims vanilla but exposes " + fp.get().display() + " channel")
                        .detectionMethod("Consistency").confidence(conf).violation(0)
                        .brand(brand).matched(fp.get().display())
                        .values(Map.of("channel", ch, "brand", brand, "verdict", "SPOOF_SUSPECTED"))
                        .build());
                return;
            }
        }
    }

    private boolean isModChannel(String ch) {
        String n = ForbiddenModRegistry.normalize(ch);
        return n.contains("xaerominimap") || n.contains("xaeroworldmap") || n.contains("journeymap")
                || n.contains("voxelmap") || n.contains("freecam") || n.contains("baritone") || n.contains("litematica");
    }
}
