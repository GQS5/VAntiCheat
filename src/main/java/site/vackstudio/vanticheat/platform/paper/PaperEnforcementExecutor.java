package site.vackstudio.vanticheat.platform.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import site.vackstudio.vanticheat.enforcement.EnforcementExecutor;
import site.vackstudio.vanticheat.enforcement.EnforcementTarget;

public final class PaperEnforcementExecutor implements EnforcementExecutor {
    @Override
    public boolean kick(EnforcementTarget target, String message) {
        if (!(target.platformHandle() instanceof Player player) || !player.isOnline()) return false;
        player.kick(Component.text(message));
        return true;
    }
}
