package site.vackstudio.vanticheat.platform.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import site.vackstudio.vanticheat.detection.probe.CheckHacksResponseEvaluator;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;

import java.util.List;
import java.util.UUID;

/** Small world-operation seam used by sign probes; callers must already be in the owning context. */
interface SignProbeWorldAccess {
    Location playerBlockLocation(Player player);
    boolean isAir(Location location);
    BlockState capture(Location location);
    void setType(Location location, Material material);
    Sign configureSign(Location location, List<ProbeDefinition> probes, UUID editor);
    boolean restore(BlockState original);

    SignProbeWorldAccess PAPER = new SignProbeWorldAccess() {
        @Override public Location playerBlockLocation(Player player) {
            return player.getLocation().getBlock().getLocation();
        }
        @Override public boolean isAir(Location location) { return location.getBlock().getType().isAir(); }
        @Override public BlockState capture(Location location) { return location.getBlock().getState(); }
        @Override public void setType(Location location, Material material) {
            location.getBlock().setType(material, false);
        }
        @Override public Sign configureSign(Location location, List<ProbeDefinition> probes, UUID editor) {
            Block block = location.getBlock();
            Sign sign = (Sign) block.getState();
            var front = sign.getSide(Side.FRONT);
            for (int i = 0; i < probes.size() && i < 3; i++) {
                ProbeDefinition probe = probes.get(i);
                front.line(i, component(probe));
            }
            front.line(3, Component.keybind(CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY));
            sign.setAllowedEditorUniqueId(editor);
            if (!sign.update(true, false)) throw new IllegalStateException("temporary sign update was rejected");
            return sign;
        }
        @Override public boolean restore(BlockState original) { return original.update(true, false); }
        private Component component(ProbeDefinition probe) {
            return switch (probe.mode()) {
                case METEOR, TRANSLATE -> Component.translatable(probe.key(), probe.fallback());
                case KEYBIND -> Component.keybind(probe.key());
            };
        }
    };
}
