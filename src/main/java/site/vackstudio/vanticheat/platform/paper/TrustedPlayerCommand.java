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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TrustedPlayerCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "[VAntiCheat] ";
    private final TrustedPlayerService trusted;
    private final Runnable reload;

    public TrustedPlayerCommand(TrustedPlayerService trusted, Runnable reload) {
        this.trusted = trusted;
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && !player.hasPermission("vanticheat.admin")) {
            sender.sendMessage(PREFIX + "You do not have permission to manage trusted players.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reload.run();
            sender.sendMessage(PREFIX + "Configuration reloaded.");
            return true;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("trust")) {
            sender.sendMessage(PREFIX + "Usage: /vac help");
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            list(sender);
            return true;
        }
        if (args.length == 2 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            sender.sendMessage(PREFIX + "Usage: /vac help");
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
        sender.sendMessage(PREFIX + "Usage: /vac help");
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(PREFIX + "Commands:");
        sender.sendMessage(PREFIX + "/vac reload - reload VAntiCheat configuration");
        sender.sendMessage(PREFIX + "/vac trust <player> - trust a player");
        sender.sendMessage(PREFIX + "/vac trust add <player> - trust a player");
        sender.sendMessage(PREFIX + "/vac trust remove <player> - remove trust");
        sender.sendMessage(PREFIX + "/vac trust list - list trusted players");
        sender.sendMessage(PREFIX + "/vacprobe <player> - run a client probe");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return matching(List.of("help", "reload", "trust"), args[0]);
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
            sender.sendMessage(PREFIX + "Player is not online or cached by the server.");
            return;
        }
        boolean added = trusted.add(resolved.id(), resolved.name());
        trusted.save();
        sender.sendMessage(PREFIX + (added ? resolved.name() + " is now trusted."
                : resolved.name() + " is already trusted."));
    }

    private void remove(CommandSender sender, String name) {
        ResolvedPlayer resolved = resolve(name);
        if (resolved == null) {
            sender.sendMessage(PREFIX + name + " is not trusted.");
            return;
        }
        boolean removed = trusted.remove(resolved.id());
        trusted.save();
        sender.sendMessage(PREFIX + (removed ? resolved.name() + " is no longer trusted."
                : name + " is not trusted."));
    }

    private void list(CommandSender sender) {
        List<TrustedPlayer> players = trusted.list();
        if (players.isEmpty()) {
            sender.sendMessage(PREFIX + "No trusted players.");
            return;
        }
        sender.sendMessage(PREFIX + "Trusted players (" + players.size() + "):");
        players.forEach(player -> sender.sendMessage(PREFIX + "- " + player.name()));
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
