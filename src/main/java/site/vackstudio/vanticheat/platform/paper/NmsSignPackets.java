package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Minecraft 1.21.11 packet bridge. This class is intentionally version-specific. */
final class NmsSignPackets {
    private static final String VERSION = "1.21.11";
    private static final Class<?> BLOCK_POS;
    private static final Constructor<?> BLOCK_POS_CONSTRUCTOR;
    private static final Class<?> BLOCK_ENTITY_PACKET;
    private static final Method BLOCK_ENTITY_CREATE;
    private static final Constructor<?> OPEN_SIGN_CONSTRUCTOR;
    private static final Method GET_HANDLE;
    private static final Field CONNECTION;
    private static final Method SEND;

    static {
        Class<?> blockPos = null;
        Constructor<?> blockPosConstructor = null;
        Class<?> blockEntityPacket = null;
        Method blockEntityCreate = null;
        Constructor<?> openSignConstructor = null;
        Method getHandle = null;
        Field connection = null;
        Method send = null;
        try {
            blockPos = Class.forName("net.minecraft.core.BlockPos");
            blockPosConstructor = blockPos.getConstructor(int.class, int.class, int.class);
            blockEntityPacket = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket");
            for (Method method : blockEntityPacket.getMethods()) {
                if (method.getName().equals("create") && method.getParameterCount() == 1) {
                    blockEntityCreate = method;
                    break;
                }
            }
            Class<?> openSign = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket");
            openSignConstructor = openSign.getConstructor(blockPos, boolean.class);
            getHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer")
                    .getMethod("getHandle");
            connection = findField(Class.forName("net.minecraft.server.level.ServerPlayer"), "connection");
            Class<?> connectionType = connection.getType();
            for (Method method : connectionType.getMethods()) {
                if (method.getName().equals("send") && method.getParameterCount() == 1) {
                    send = method;
                    break;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // The transport reports this failure per operation; plugin startup remains safe.
        }
        BLOCK_POS = blockPos;
        BLOCK_POS_CONSTRUCTOR = blockPosConstructor;
        BLOCK_ENTITY_PACKET = blockEntityPacket;
        BLOCK_ENTITY_CREATE = blockEntityCreate;
        OPEN_SIGN_CONSTRUCTOR = openSignConstructor;
        GET_HANDLE = getHandle;
        CONNECTION = connection;
        SEND = send;
    }

    private NmsSignPackets() { }

    static boolean sendBlockEntityPacket(Player player, Location location, Plugin plugin) {
        try {
            Object handle = GET_HANDLE.invoke(player);
            Object world = location.getWorld().getClass().getMethod("getHandle").invoke(location.getWorld());
            Object blockPos = blockPos(location);
            Method getBlockEntity = findBlockEntityMethod(world.getClass());
            Object blockEntity = getBlockEntity.invoke(world, blockPos);
            if (blockEntity == null) throw new IllegalStateException("sign block entity missing");
            Object packet = BLOCK_ENTITY_CREATE.invoke(null, blockEntity);
            SEND.invoke(CONNECTION.get(handle), packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Client probe block entity packet failed for " + VERSION + ": "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return false;
        }
    }

    static boolean sendOpenSignPacket(Player player, Location location, Plugin plugin) {
        try {
            Object handle = GET_HANDLE.invoke(player);
            Object packet = OPEN_SIGN_CONSTRUCTOR.newInstance(blockPos(location), true);
            SEND.invoke(CONNECTION.get(handle), packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Client probe open-sign packet failed for " + VERSION + ": "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return false;
        }
    }

    private static Object blockPos(Location location) throws ReflectiveOperationException {
        return BLOCK_POS_CONSTRUCTOR.newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static Method findBlockEntityMethod(Class<?> worldType) throws NoSuchMethodException {
        for (Method method : worldType.getMethods()) {
            if (method.getName().equals("getBlockEntity") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(BLOCK_POS)) return method;
        }
        throw new NoSuchMethodException("getBlockEntity(BlockPos)");
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
