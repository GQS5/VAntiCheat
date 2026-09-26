package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import site.vackstudio.vanticheat.config.Messages;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.enforcement.EnforcementOutcome;
import site.vackstudio.vanticheat.enforcement.EnforcementService;
import site.vackstudio.vanticheat.enforcement.EnforcementTarget;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public final class ClientProbeCommand implements CommandExecutor {
    private final CheckHacksClientDetectionModule module;
    private final EnforcementService enforcement;
    private final Logger logger;
    private final Messages messages;

    public ClientProbeCommand(CheckHacksClientDetectionModule module, EnforcementService enforcement,
                              Logger logger, Messages messages) {
        this.module = module;
        this.enforcement = enforcement;
        this.logger = logger;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(messages.render("probe.usage"));
            return true;
        }
        check(sender, args[0]);
        return true;
    }

    public void check(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(messages.render("probe.offline"));
            return;
        }
        UUID id = target.getUniqueId();
        sender.sendMessage(messages.render("probe.start", java.util.Map.of("player", target.getName())));
        module.check(new DetectionTarget(id, target.getName(), true, target), result -> {
            EnforcementOutcome outcome = enforcement.enforce(
                    new EnforcementTarget(id, target.getName(), target.isOnline(), target), result);
            report(sender, target, outcome);
        });
    }

    private void report(CommandSender sender, Player target, EnforcementOutcome outcome) {
        DetectionResult result = outcome.result();
        String mods = detectedMods(result);
        String session = sessionId(result);
        logger.info("Client probe result player=" + target.getName() + " uuid=" + target.getUniqueId()
                + " status=" + result.status() + " resultReason=" + result.reason()
                + " action=" + outcome.decision().action() + " reason=" + outcome.decision().reason()
                + " evidence=" + result.evidence().size() + " mods=" + mods + " session=" + session);
        sender.sendMessage(messages.render("probe.result", java.util.Map.of(
                "player", target.getName(), "status", result.status(),
                "evidence", result.evidence().size(), "mods", mods,
                "action", outcome.decision().action(), "reason", outcome.decision().reason(),
                "session", session)));
    }

    static String sessionId(DetectionResult result) {
        return result.evidence().stream()
                .map(item -> item.metadata().get("session"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("unknown");
    }

    private String detectedMods(DetectionResult result) {
        List<String> mods = CheckHacksClientDetectionModule.detectedProbeIds(result).stream()
                .map(module::displayName)
                .toList();
        return mods.isEmpty() ? "none" : String.join(", ", mods);
    }
}
