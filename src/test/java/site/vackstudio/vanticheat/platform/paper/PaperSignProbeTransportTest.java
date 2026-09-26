package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperSignProbeTransportTest {
    @Test
    void candidatesAvoidSolidGroundBelowVanillaPlayers() {
        Location playerFeet = new Location(null, 0, 64, 0);

        assertTrue(PaperSignProbeTransport.candidates(playerFeet).stream()
                .allMatch(candidate -> candidate.getBlockY() > playerFeet.getBlockY()));
    }

    @Test
    void calculatesChunkCoordinatesWithoutRetrievingChunks() {
        assertEquals(-2, PaperSignProbeTransport.chunkCoordinate(-17));
        assertEquals(-1, PaperSignProbeTransport.chunkCoordinate(-1));
        assertEquals(-1, PaperSignProbeTransport.chunkCoordinate(-16));
        assertEquals(0, PaperSignProbeTransport.chunkCoordinate(15));
        assertEquals(1, PaperSignProbeTransport.chunkCoordinate(16));
    }
}
