package com.hexa.vanticheat.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny message renderer: {@code &} color codes + {@code %name%}/{@code {name}}
 * placeholders → Adventure Component. No extra dependencies (adventure-api only,
 * stable across Paper/Folia versions). Used for commands, alerts and kick
 * screens — never on detection hot paths. Templates are pre-parsed once and
 * cached by {@link Messages}; placeholder values are inserted as plain text
 * (never re-parsed, so player names can't inject colors).
 */
public final class MessageFormat {

    private MessageFormat() {}

    private sealed interface Token permits Text, Code, Placeholder {}
    private record Text(String s) implements Token {}
    private record Code(char c) implements Token {}
    private record Placeholder(String name) implements Token {}

    /** Pre-parsed template. Immutable, thread-safe, cheap to render. */
    public static final class Template {
        private final List<Token> tokens;
        private Template(List<Token> tokens) { this.tokens = List.copyOf(tokens); }

        public Component render(Map<String, String> values) {
            TextComponent.Builder out = Component.text();
            StringBuilder lit = new StringBuilder();
            NamedTextColor color = null;
            List<TextDecoration> dec = new ArrayList<>(2);
            for (Token t : tokens) {
                if (t instanceof Text x) lit.append(x.s());
                else if (t instanceof Placeholder ph) {
                    String v = values.get(ph.name());
                    if (v != null) lit.append(v);
                } else if (t instanceof Code code) {
                    flush(out, lit, color, dec);
                    char c = Character.toLowerCase(code.c());
                    if (c == 'r') { color = null; dec.clear(); }
                    else {
                        NamedTextColor nc = colorOf(c);
                        if (nc != null) color = nc;
                        else {
                            TextDecoration td = decorOf(c);
                            if (td != null && !dec.contains(td)) dec.add(td);
                        }
                    }
                }
            }
            flush(out, lit, color, dec);
            return out.build();
        }

        private static void flush(TextComponent.Builder out, StringBuilder lit,
                                  NamedTextColor color, List<TextDecoration> dec) {
            if (lit.length() == 0) return;
            var b = Component.text(lit.toString());
            if (color != null) b = b.color(color);
            for (TextDecoration d : dec) b = b.decorate(d);
            out.append(b);
            lit.setLength(0);
        }
    }

    public static Template parse(String template) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int i = 0, n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '&' && i + 1 < n && isCode(template.charAt(i + 1))) {
                if (lit.length() > 0) { tokens.add(new Text(lit.toString())); lit.setLength(0); }
                tokens.add(new Code(template.charAt(i + 1)));
                i += 2;
            } else if (c == '%' && i + 1 < n) {
                int end = template.indexOf('%', i + 1);
                if (end > i + 1 && end - i < 32 && isName(template, i + 1, end)) {
                    if (lit.length() > 0) { tokens.add(new Text(lit.toString())); lit.setLength(0); }
                    tokens.add(new Placeholder(template.substring(i + 1, end)));
                    i = end + 1;
                } else { lit.append(c); i++; }
            } else if (c == '{' && i + 1 < n) {
                int end = template.indexOf('}', i + 1);
                if (end > i + 1 && end - i < 32 && isName(template, i + 1, end)) {
                    if (lit.length() > 0) { tokens.add(new Text(lit.toString())); lit.setLength(0); }
                    tokens.add(new Placeholder(template.substring(i + 1, end)));
                    i = end + 1;
                } else { lit.append(c); i++; }
            } else { lit.append(c); i++; }
        }
        if (lit.length() > 0) tokens.add(new Text(lit.toString()));
        return new Template(tokens);
    }

    public static Component render(String template, Map<String, String> values) {
        return parse(template).render(values);
    }

    private static boolean isCode(char c) {
        c = Character.toLowerCase(c);
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == 'r'
                || c == 'l' || c == 'm' || c == 'n' || c == 'o' || c == 'k';
    }

    private static boolean isName(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!(c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')))
                return false;
        }
        return true;
    }

    private static NamedTextColor colorOf(char c) {
        return switch (c) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    private static TextDecoration decorOf(char c) {
        return switch (c) {
            case 'l' -> TextDecoration.BOLD;
            case 'm' -> TextDecoration.STRIKETHROUGH;
            case 'n' -> TextDecoration.UNDERLINED;
            case 'o' -> TextDecoration.ITALIC;
            case 'k' -> TextDecoration.OBFUSCATED;
            default -> null;
        };
    }
}
