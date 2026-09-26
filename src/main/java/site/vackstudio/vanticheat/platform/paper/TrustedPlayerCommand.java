package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import site.vackstudio.vanticheat.trusted.TrustedPlayer;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;
import site.vackstudio.vanticheat.config.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.BiConsumer;

public final class TrustedPlayerCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "[VAntiCheat] ";
    private final TrustedPlayerService trusted;
    private final Runnable reload;
    private final BiConsumer<CommandSender, String> probe;
    private final Messages messages;

    public TrustedPlayerCommand(TrustedPlayerService trusted, Runnable reload,
                                BiConsumer<CommandSender, String> probe, Messages messages) {
        this.trusted = trusted;
        this.reload = Objects.requireNonNull(reload, "reload");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            if (sender instanceof Player player && !player.hasPermission("vanticheat.probe")) {
                sender.sendMessage(messages.render("command.no-permission-probe"));
                return true;
            }
            probe.accept(sender, args[1]);
            return true;
        }
        if (sender instanceof Player player && !player.hasPermission("vanticheat.admin")) {
            sender.sendMessage(messages.render("command.no-permission-admin"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reload.run();
            sender.sendMessage(messages.render("command.reloaded"));
            return true;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("trust")) {
            sender.sendMessage(messages.render("command.usage"));
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            list(sender);
            return true;
        }
        if (args.length == 2 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            sender.sendMessage(messages.render("command.usage"));
            return true;
        }
        if (args.length == 2) {
            add(sender, args[1]);
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
            add(sender, args[2]);
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            remove(sender, args[2]);
            return true;
        }
        sender.sendMessage(messages.render("command.usage"));
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(messages.render("command.help.header"));
        sender.sendMessage(messages.render("command.help.reload"));
        sender.sendMessage(messages.render("command.help.trust"));
        sender.sendMessage(messages.render("command.help.trust-add"));
        sender.sendMessage(messages.render("command.help.trust-remove"));
        sender.sendMessage(messages.render("command.help.trust-list"));
        sender.sendMessage(messages.render("command.help.check"));
        sender.sendMessage(messages.render("command.help.probe"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return matching(List.of("help", "reload", "trust"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("trust")) {
            List<String> values = new ArrayList<>(List.of("add", "remove", "list"));
            Bukkit.getOnlinePlayers().forEach(player -> values.add(player.getName()));
            return matching(values, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("trust")
                && args[1].equalsIgnoreCase("remove")) {
            return matching(trusted.list().stream().map(TrustedPlayer::name).toList(), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("trust")
                && args[1].equalsIgnoreCase("add")) {
            return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        return List.of();
    }

    private void add(CommandSender sender, String name) {
        ResolvedPlayer resolved = resolve(name);
        if (resolved == null) {
            sender.sendMessage(messages.render("command.trust.not-found"));
            return;
        }
        boolean added = trusted.add(resolved.id(), resolved.name());
        trusted.save();
        sender.sendMessage(messages.render(added ? "command.trust.added" : "command.trust.exists",
                java.util.Map.of("player", resolved.name())));
    }

    private void remove(CommandSender sender, String name) {
        ResolvedPlayer resolved = resolve(name);
        if (resolved == null) {
            sender.sendMessage(messages.render("command.trust.not-trusted", java.util.Map.of("player", name)));
            return;
        }
        boolean removed = trusted.remove(resolved.id());
        trusted.save();
        sender.sendMessage(messages.render(removed ? "command.trust.removed" : "command.trust.not-trusted",
                java.util.Map.of("player", removed ? resolved.name() : name)));
    }

    private void list(CommandSender sender) {
        List<TrustedPlayer> players = trusted.list();
        if (players.isEmpty()) {
            sender.sendMessage(messages.render("command.trust.empty"));
            return;
        }
        sender.sendMessage(messages.render("command.trust.header", java.util.Map.of("count", players.size())));
        players.forEach(player -> sender.sendMessage(messages.render("command.trust.entry",
                java.util.Map.of("player", player.name()))));
    }

    private ResolvedPlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new ResolvedPlayer(online.getUniqueId(), online.getName());
        for (TrustedPlayer player : trusted.list()) {
            if (player.name().equalsIgnoreCase(name)) return new ResolvedPlayer(player.id(), player.name());
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null || cached.getUniqueId() == null) return null;
        return new ResolvedPlayer(cached.getUniqueId(), cached.getName() == null ? name : cached.getName());
    }

    private static List<String> matching(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
    }

    private record ResolvedPlayer(UUID id, String name) { }
}
