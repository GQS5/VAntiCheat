package com.hexa.vanticheat.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player client state. Thread-safe; written on netty/login threads, read on region threads. */
public final class ClientProfile {

    private final UUID playerId;
    private volatile String playerName;
    private volatile String brand = "unknown";
    private volatile long brandAt;
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private volatile String matchedClient = "";
    private volatile String matchedMod = "";
    private volatile boolean probed;

    public ClientProfile(UUID playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName;
    }

    public UUID playerId() { return playerId; }
    public String playerName() { return playerName; }
    public void playerName(String n) { this.playerName = n; }
    public String brand() { return brand; }
    public void brand(String b) { this.brand = b; this.brandAt = System.currentTimeMillis(); }
    public long brandAt() { return brandAt; }
    public Set<String> channels() { return channels; }
    public String matchedClient() { return matchedClient; }
    public void matchedClient(String s) { this.matchedClient = s; }
    public String matchedMod() { return matchedMod; }
    public void matchedMod(String s) { this.matchedMod = s; }
    public boolean probed() { return probed; }
    public void probed(boolean p) { this.probed = p; }
}
