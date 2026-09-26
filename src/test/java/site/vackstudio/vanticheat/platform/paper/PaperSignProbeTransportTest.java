package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperSignProbeTransportTest {
    @Test
    void candidatesAvoidSolidGroundBelowVanillaPlayers() {
        Location playerFeet = new Location(null, 0, 64, 0);

        assertTrue(PaperSignProbeTransport.candidates(playerFeet).stream()
                .allMatch(candidate -> candidate.getBlockY() > playerFeet.getBlockY()));
    }
}
