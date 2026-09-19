package site.vackstudio.vanticheat.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand.Invocation;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import java.util.Arrays;
import java.util.List;
import site.vackstudio.vanticheat.VAntiCheatPlugin;
import site.vackstudio.vanticheat.config.PluginConfig;

public class VacCommand implements SimpleCommand {

    private final VAntiCheatPlugin plugin;

    public VacCommand(VAntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        List<String> args = Arrays.asList(invocation.arguments());

        if (args.isEmpty()) {
            source.sendMessage(Component.text("VAntiCheat 0.1.0 - Pre-backend verification firewall"));
            source.sendMessage(Component.text("Usage: /vac <reload|status|mods>"));
            return;
        }

        switch (args.get(0).toLowerCase()) {
            case "reload" -> handleReload(source);
            case "status" -> handleStatus(source);
            case "mods" -> handleMods(source);
            default -> source.sendMessage(Component.text("Unknown subcommand: " + args.get(0)));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("reload", "status", "mods");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("vanti.command");
    }

    private void handleReload(CommandSource source) {
        if (!source.hasPermission("vanti.admin")) {
            source.sendMessage(Component.text("Permission denied."));
            return;
        }
        plugin.reloadConfig();
        source.sendMessage(Component.text("Configuration reloaded."));
    }

    private void handleStatus(CommandSource source) {
        if (!source.hasPermission("vanti.admin")) {
            source.sendMessage(Component.text("Permission denied."));
            return;
        }
        PluginConfig config = plugin.getConfig();
        source.sendMessage(Component.text("VAntiCheat version: " + config.getPluginVersion()));
        source.sendMessage(Component.text("Enabled: " + plugin.isEnabled()));
        source.sendMessage(Component.text("Verification enabled: " + config.isVerificationEnabled()));
        source.sendMessage(Component.text("Blocklist rules: " + plugin.getBlocklist().ruleCount()));
        source.sendMessage(Component.text("Active sessions: " + plugin.getSessionManager().activeSessionCount()));
        source.sendMessage(Component.text("Protocol version: " + config.getProtocolVersion()));
    }

    private void handleMods(CommandSource source) {
        if (!source.hasPermission("vanti.mods")) {
            source.sendMessage(Component.text("Permission denied."));
            return;
        }
        var rules = plugin.getBlocklist().getRules();
        source.sendMessage(Component.text("Blocklist rules (" + rules.size() + "):"));
        for (var rule : rules) {
            source.sendMessage(Component.text("  " + rule.getRuleId() + ": enabled=" + rule.isEnabled() + " action=" + rule.getAction()));
        }
    }
}