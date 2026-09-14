package com.hexa.vanticheat.integration;

/**
 * Packet-library isolation (§31/§34). PacketEvents (or any sniffer) stays
 * behind this adapter; detectors never import packet types. Disabled by
 * default; engine runs fully without it.
 */
public final class PacketAdapter {
    public enum Mode { DISABLED, PASSIVE }

    private volatile Mode mode = Mode.DISABLED;
    private volatile boolean available;

    public PacketAdapter() {
        try { Class.forName("com.github.retrooper.packetevents.PacketEvents"); available = true; }
        catch (Throwable t) { available = false; }
    }

    public boolean available() { return available; }
    public Mode mode() { return available ? mode : Mode.DISABLED; }
    public void setMode(Mode m) { this.mode = m; }
}
