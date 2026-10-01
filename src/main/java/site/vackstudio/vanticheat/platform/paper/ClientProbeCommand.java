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
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeRegistry;
import site.vackstudio.vanticheat.platform.ClientPlatformService;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
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
    private final ClientPlatformService platforms;
    private final Scheduler scheduler;

    public ClientProbeCommand(CheckHacksClientDetectionModule module, EnforcementService enforcement,
                              Logger logger, Messages messages) {
        this(module, enforcement, logger, messages, null, null);
    }

    public ClientProbeCommand(CheckHacksClientDetectionModule module, EnforcementService enforcement,
                              Logger logger, Messages messages, ClientPlatformService platforms) {
        this(module, enforcement, logger, messages, platforms, null);
    }

    public ClientProbeCommand(CheckHacksClientDetectionModule module, EnforcementService enforcement,
                              Logger logger, Messages messages, ClientPlatformService platforms,
                              Scheduler scheduler) {
        this.module = module;
        this.enforcement = enforcement;
        this.logger = logger;
        this.messages = messages;
        this.platforms = platforms;
        this.scheduler = scheduler;
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
        check(sender, playerName, null);
    }

    public void check(CommandSender sender, String playerName, List<String> probeIds) {
        if (!module.isStarted()) {
            sender.sendMessage(messages.render("probe.disabled"));
            return;
        }
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(messages.render("probe.offline"));
            return;
        }
        ClientPlatformService.Classification classification = platforms == null ? null
                : platforms.refresh(target.getUniqueId());
        if (classification != null && !classification.canProbe()) {
            sender.sendMessage(messages.render(classification.state() == ClientPlatformService.State.BEDROCK
                    ? "probe.bedrock-skipped" : "probe.platform-pending"));
            return;
        }
        if (enforcement.isTrusted(target.getUniqueId())) {
            sender.sendMessage(messages.render("probe.trusted-skipped"));
            return;
        }
        UUID id = target.getUniqueId();
        if (module.isActive(id)) {
            sender.sendMessage(messages.render("probe.busy"));
            return;
        }
        List<ProbeDefinition> selected = selectProbes(sender, probeIds);
        if (selected == null) return;
        sender.sendMessage(messages.render("probe.start", java.util.Map.of("player", target.getName())));
        module.check(new DetectionTarget(id, target.getName(), true, target), selected, result -> {
            if (result.status() == site.vackstudio.vanticheat.detection.DetectionStatus.SKIPPED) {
                return;
            }
            Runnable apply = () -> enforceAndReport(sender, target, id, result);
            if (scheduler == null) {
                apply.run();
                return;
            }
            try {
                scheduler.runAtEntity(new EntityTarget(target), apply,
                        () -> enforcementSchedulingFailed(result, target.getName(),
                                "target entity retired before enforcement"));
            } catch (RuntimeException exception) {
                enforcementSchedulingFailed(result, target.getName(), exception.getClass().getSimpleName());
            }
        });
    }

    private void enforceAndReport(CommandSender sender, Player target, UUID id, DetectionResult result) {
        try {
            if (!module.isStarted()) {
                module.enforcementComplete(result, "SHUTDOWN");
                return;
            }
            if (platforms != null && !platforms.refresh(id).canProbe()) {
                module.enforcementComplete(result, "PLATFORM_INELIGIBLE");
                return;
            }
            EnforcementOutcome outcome = enforcement.enforce(
                    new EnforcementTarget(id, target.getName(), target.isOnline(), target), result);
            module.enforcementComplete(result, outcome.decision().action().name());
            report(sender, target, outcome);
        } catch (RuntimeException exception) {
            logger.warning("Manual client enforcement failed player=" + target.getName()
                    + " uuid=" + id + " reason=" + exception.getClass().getSimpleName());
            module.enforcementComplete(result, "ERROR");
        }
    }

    private void enforcementSchedulingFailed(DetectionResult result, String playerName, String reason) {
        logger.warning("Manual client enforcement scheduling failed player=" + playerName + " reason=" + reason);
        module.enforcementComplete(result, "ERROR");
    }

    private List<ProbeDefinition> selectProbes(CommandSender sender, List<String> probeIds) {
        ProbeRegistry registry = module.registry();
        if (probeIds == null) return registry.manual();
        List<ProbeDefinition> selected = new java.util.ArrayList<>();
        for (String id : probeIds) {
            ProbeDefinition probe = registry.find(id);
            if (probe == null) {
                sender.sendMessage(messages.render("probe.unknown", java.util.Map.of("probe", id)));
                return null;
            }
            if (!probe.enabled()) {
                sender.sendMessage(messages.render("probe.disabled-id", java.util.Map.of("probe", id)));
                return null;
            }
            if (!probe.manual()) {
                sender.sendMessage(messages.render("probe.manual-disabled", java.util.Map.of("probe", id)));
                return null;
            }
            if (selected.stream().noneMatch(candidate -> candidate.id().equals(probe.id()))) selected.add(probe);
        }
        return List.copyOf(selected);
    }

    private void report(CommandSender sender, Player target, EnforcementOutcome outcome) {
        DetectionResult result = outcome.result();
        String mods = detectedMods(result);
        String session = sessionId(result);
        logger.info("Client probe result player=" + target.getName() + " uuid=" + target.getUniqueId()
                + " status=" + result.status() + " resultReason=" + result.reason()
                + " action=" + outcome.decision().action() + " reason=" + outcome.decision().reason()
                + " evidence=" + result.evidence().size() + " mods=" + mods + " session=" + session);
        String message = messages.render("probe.result", java.util.Map.of(
                "player", target.getName(), "status", result.status(),
                "evidence", result.evidence().size(), "mods", mods,
                "action", outcome.decision().action(), "reason", outcome.decision().reason(),
                "session", session));
        if (scheduler != null && sender instanceof Player senderPlayer && senderPlayer != target) {
            try {
                scheduler.runAtEntity(new EntityTarget(senderPlayer), () -> sender.sendMessage(message),
                        () -> logger.fine("Probe result message target retired before delivery"));
            } catch (RuntimeException exception) {
                logger.fine("Probe result message scheduling failed: " + exception.getClass().getSimpleName());
            }
        } else {
            sender.sendMessage(message);
        }
    }

    static String sessionId(DetectionResult result) {
        return result.evidence().stream()
                .map(item -> item.metadata().get("session"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("unknown");
    }

    private String detectedMods(DetectionResult result) {
        List<String> mods = CheckHacksClientDetectionModule.detectedProbeDisplayNames(result);
        return mods.isEmpty() ? "none" : String.join(", ", mods);
    }
}
