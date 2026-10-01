package site.vackstudio.vanticheat.platform.paper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VAntiCheatCommandTest {
    @Test
    void parsesCommaSeparatedProbeIds() {
        assertEquals(List.of("meteor-client", "freecam", "xaeros-minimap"),
                VAntiCheatCommand.splitProbeIds("meteor-client, freecam,xaeros-minimap,meteor-client"));
    }

    @Test
    void ignoresEmptyProbeIds() {
        assertEquals(List.of("freecam"), VAntiCheatCommand.splitProbeIds(", freecam, "));
    }

    @Test
    void tabCompletionMatchesCaseInsensitively() {
        assertEquals(List.of("meteor-client"), VAntiCheatCommand.matching(
                List.of("freecam", "meteor-client"), "MET"));
    }

    @Test
    void permissionGateAllowsConsoleAndRejectsUnauthorizedPlayers() {
        assertTrue(VAntiCheatCommand.authorized(false, false), "console is not permission-gated");
        assertTrue(VAntiCheatCommand.authorized(true, true));
        assertFalse(VAntiCheatCommand.authorized(true, false));
    }
}
