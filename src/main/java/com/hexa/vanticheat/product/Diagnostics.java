package com.hexa.vanticheat.product;

import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.integration.PacketAdapter;
import com.hexa.vanticheat.integration.ViaVersionAdapter;
import com.hexa.vanticheat.platform.ServerPlatform;
import com.hexa.vanticheat.version.ServerProfile;

/**
 * Commercial diagnostics (§36/§43): platform, versions, integrations, modules,
 * queues, memory, latencies. No sensitive security internals exposed.
 */
public final class Diagnostics {
    private Diagnostics() {}

    public static String status(VAntiCheat plugin, ServerPlatform platform, ServerProfile server,
                                ViaVersionAdapter via, PacketAdapter packets) {
        StringBuilder sb = new StringBuilder();
        sb.append("VAntiCheat ").append(plugin.getDescription().getVersion());
        sb.append(" | platform=").append(platform);
        sb.append(" mc=").append(server.minecraftVersion()).append("(").append(server.tier()).append(")");
        sb.append(" java=").append(Runtime.version().feature());
        sb.append(" | viaversion=").append(via.available() ? "yes" : "no");
        sb.append(" packets=").append(packets.mode());
        sb.append(" | profile=").append(plugin.vacConfig().snapshot().profile);
        sb.append(" | ").append(plugin.profiler().summary());
        return sb.toString();
    }
}
