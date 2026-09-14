package com.hexa.vanticheat.qa;

import com.hexa.vanticheat.core.MessageFormat;
import com.hexa.vanticheat.punishment.Action;
import com.hexa.vanticheat.punishment.PunishmentPolicy;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Message CMS + punishment-gate unit tests (no server needed). */
public class MessageAndPolicyTest {

    private static String plain(String template, Map<String, String> v) {
        return PlainTextComponentSerializer.plainText().serialize(MessageFormat.render(template, v));
    }

    @Test
    public void colorCodesRender() {
        var c = MessageFormat.render("&aHello &cWorld", Map.of());
        String s = PlainTextComponentSerializer.plainText().serialize(c);
        assertEquals("Hello World", s);
    }

    @Test
    public void placeholdersSubstitute() {
        assertEquals("Hi Steve (71%)", plain("Hi %player% ({confidence}%)", Map.of("player", "Steve", "confidence", "71")));
        assertEquals("Hi Steve", plain("Hi {player}", Map.of("player", "Steve")));
    }

    @Test
    public void valuesCannotInjectColors() {
        // A player named "&cAdmin" must stay literal text, not re-parsed.
        var c = MessageFormat.render("&7Player: %player%", Map.of("player", "&cAdmin"));
        assertEquals("Player: &cAdmin", PlainTextComponentSerializer.plainText().serialize(c));
    }

    @Test
    public void malformedTemplatesNeverThrow() {
        assertDoesNotThrow(() -> MessageFormat.render("&", Map.of()));
        assertDoesNotThrow(() -> MessageFormat.render("%unclosed", Map.of()));
        assertDoesNotThrow(() -> MessageFormat.render("{unclosed", Map.of()));
        assertDoesNotThrow(() -> MessageFormat.render("&x &% &{", Map.of()));
        assertEquals("100%", plain("100%", Map.of()));
    }

    @Test
    public void singleWeakSignalNeverBans() {
        assertFalse(PunishmentPolicy.diversityAllows(Action.BAN, 1, true, 2));
        assertFalse(PunishmentPolicy.diversityAllows(Action.TEMPBAN, 1, true, 2));
        assertTrue(PunishmentPolicy.diversityAllows(Action.BAN, 2, true, 2));
        assertTrue(PunishmentPolicy.diversityAllows(Action.BAN, 1, false, 2));
        assertTrue(PunishmentPolicy.diversityAllows(Action.KICK, 1, true, 2));
        assertTrue(PunishmentPolicy.diversityAllows(Action.WARN, 0, true, 2));
    }
}
