package site.vackstudio.vanticheat.trusted;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentTrustedPlayerServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void addDuplicateNameChangeSaveAndReloadUseUuidAuthority() {
        Path file = temporaryDirectory.resolve("data/trusted-players.yml");
        UUID id = UUID.randomUUID();
        PersistentTrustedPlayerService service = service(file);

        assertTrue(service.add(id, "FirstName"));
        assertFalse(service.add(id, "Renamed"));
        service.save();

        PersistentTrustedPlayerService reloaded = service(file);
        reloaded.load();
        assertTrue(reloaded.isTrusted(id));
        assertEquals("Renamed", reloaded.list().getFirst().name());
        assertEquals(1, reloaded.list().size());
    }

    @Test
    void removeAndMalformedEntriesAreSafe() throws Exception {
        Path file = temporaryDirectory.resolve("trusted-players.yml");
        UUID valid = UUID.randomUUID();
        Files.writeString(file, """
                trusted-players:
                  not-a-uuid:
                    name: Bad
                  %s:
                    name: Good
                    added-at: %s
                  %s:
                    name: LastName
                """.formatted(valid, Instant.EPOCH, valid));

        PersistentTrustedPlayerService service = service(file);
        service.load();
        assertTrue(service.isTrusted(valid));
        assertEquals("LastName", service.list().getFirst().name());
        assertTrue(service.remove(valid));
        assertFalse(service.remove(valid));
        assertTrue(service.list().isEmpty());
    }

    private PersistentTrustedPlayerService service(Path file) {
        return new PersistentTrustedPlayerService(file, Logger.getAnonymousLogger());
    }
}
