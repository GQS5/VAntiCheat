package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.enforcement.EnforcementOutcome;
import site.vackstudio.vanticheat.enforcement.EnforcementService;
import site.vackstudio.vanticheat.enforcement.EnforcementTarget;

import java.util.UUID;
import java.util.logging.Logger;

public final class ClientProbeCommand implements CommandExecutor {
    private final CheckHacksClientDetectionModule module;
    private final EnforcementService enforcement;
    private final Logger logger;

    public ClientProbeCommand(CheckHacksClientDetectionModule module, EnforcementService enforcement, Logger logger) {
        this.module = module;
        this.enforcement = enforcement;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("Usage: /vacprobe <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("Player is not online");
            return true;
        }
        UUID id = target.getUniqueId();
        sender.sendMessage("Starting client probe for " + target.getName());
        module.check(new DetectionTarget(id, target.getName(), true, target), result -> {
            EnforcementOutcome outcome = enforcement.enforce(
                    new EnforcementTarget(id, target.getName(), target.isOnline(), target), result);
            report(sender, target, outcome);
        });
        return true;
    }

    private void report(CommandSender sender, Player target, EnforcementOutcome outcome) {
        DetectionResult result = outcome.result();
        logger.info("Client probe result player=" + target.getName() + " status=" + result.status()
                + " action=" + outcome.decision().action() + " evidence=" + result.evidence().size());
        sender.sendMessage("Client probe " + target.getName() + ": " + result.status()
                + " (evidence=" + result.evidence().size() + ")");
    }
}
