package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Packet boundary for sign probes; implementations never mutate world state. */
interface SignProbePacketAccess {
    Object createBlockEntityPacket(Location location, Plugin plugin);
    boolean sendBlockEntity(Player player, Object packet, Plugin plugin);
    boolean openEditor(Player player, Location location, Plugin plugin);
    void hideTemporarySign(Player player, Location location);

    SignProbePacketAccess PAPER = new SignProbePacketAccess() {
        @Override public Object createBlockEntityPacket(Location location, Plugin plugin) {
            return NmsSignPackets.createBlockEntityPacket(location, plugin);
        }
        @Override public boolean sendBlockEntity(Player player, Object packet, Plugin plugin) {
            return NmsSignPackets.sendBlockEntityPacket(player, packet, plugin);
        }
        @Override public boolean openEditor(Player player, Location location, Plugin plugin) {
            return NmsSignPackets.sendOpenSignPacket(player, location, plugin);
        }
        @Override public void hideTemporarySign(Player player, Location location) {
            player.sendBlockChange(location, Material.AIR.createBlockData());
        }
    };
}
