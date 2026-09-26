package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {
    @Test
    void bundledMessagesRenderColorsAndPlaceholders() {
        Messages messages = Messages.load(null, Logger.getAnonymousLogger());

        assertEquals("Client probe Alex: CLEAN (evidence=3, mods=none, action=NONE)",
                messages.render("probe.result", Map.of("player", "Alex", "status", "CLEAN", "evidence", 3,
                        "mods", "none", "action", "NONE")));
    }

    @Test
    void customMessagesOverrideDefaults() throws Exception {
        Path directory = Files.createTempDirectory("vanticheat-messages");
        Files.writeString(directory.resolve("messages.yml"),
                "prefix: '&a[VAC] '\nprobe.offline: '%prefix%offline: %player%'\n");

        Messages messages = Messages.load(directory, Logger.getAnonymousLogger());

        assertEquals("\u00a7a[VAC] offline: Alex",
                messages.render("probe.offline", Map.of("player", "Alex")));
    }
}
