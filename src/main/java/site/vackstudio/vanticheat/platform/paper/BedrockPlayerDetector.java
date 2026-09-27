package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

final class BedrockPlayerDetector {
    private BedrockPlayerDetector() { }

    static boolean isBedrock(Player player) {
        UUID id = player.getUniqueId();
        return isFloodgatePlayer(id) || isGeyserPlayer(id);
    }

    private static boolean isFloodgatePlayer(UUID id) {
        return invokeBoolean("org.geysermc.floodgate.api.FloodgateApi", "getInstance", id);
    }

    private static boolean isGeyserPlayer(UUID id) {
        return invokeBoolean("org.geysermc.geyser.api.GeyserApi", "api", id);
    }

    private static boolean invokeBoolean(String className, String accessor, UUID id) {
        try {
            Class<?> apiClass = Class.forName(className);
            Object api = apiClass.getMethod(accessor).invoke(null);
            return Boolean.TRUE.equals(apiClass.getMethod("isBedrockPlayer", UUID.class).invoke(api, id));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException ignored) {
            return false;
        }
    }
}
