package com.hexa.vanticheat.qa;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Bot harness: proxy-based fake players/entities for headless gameplay simulation.
 * No server needed; exercises real detector code paths (movement/combat/interaction).
 */
public final class BotHarness {

    private BotHarness() {}

    public static class Bot {
        public final UUID id = UUID.randomUUID();
        public String name = "Bot";
        public int ping = 40;
        public boolean sprinting = true;
        public boolean onGround = true;
        public Location loc = new Location(null, 0, 64, 0);
        public final Player player;

        public Bot() {
            Bot self = this;
            player = (Player) Proxy.newProxyInstance(BotHarness.class.getClassLoader(),
                    new Class<?>[]{Player.class}, new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method m, Object[] a) {
                            return switch (m.getName()) {
                                case "getUniqueId" -> self.id;
                                case "getName" -> self.name;
                                case "getPing" -> self.ping;
                                case "getGameMode" -> GameMode.SURVIVAL;
                                case "isGliding", "isInsideVehicle", "getAllowFlight" -> false;
                                case "isSprinting" -> self.sprinting;
                                case "isOnGround" -> self.onGround;
                                case "getFallDistance" -> 0.0f;
                                case "getEyeLocation" -> self.loc.clone().add(0, 1.62, 0);
                                case "getLocation" -> self.loc.clone();
                                case "getVelocity" -> new Vector(0, 0, 0);
                                case "hasPotionEffect" -> false;
                                case "hasPermission" -> false;
                                case "isOp" -> false;
                                case "hashCode" -> self.id.hashCode();
                                case "equals" -> proxy == a[0];
                                case "toString" -> "BotPlayer(" + self.name + ")";
                                default -> defaultValue(m.getReturnType());
                            };
                        }
                    });
        }
    }

    public static Entity dummyEntity(Location at) {
        Location copy = at.clone();
        UUID id = UUID.randomUUID();
        return (Entity) Proxy.newProxyInstance(BotHarness.class.getClassLoader(),
                new Class<?>[]{Entity.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "getLocation" -> copy.clone();
                    case "getVelocity" -> new Vector(0, 0, 0);
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == a[0];
                    case "toString" -> "DummyEntity";
                    default -> defaultValue(m.getReturnType());
                });
    }

    public static org.bukkit.event.entity.EntityDamageByEntityEvent hitEvent(Player attacker, Entity target) {
        return new org.bukkit.event.entity.EntityDamageByEntityEvent(attacker, target,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == float.class) return 0.0f;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return false;
    }

    /** Percentiles over sorted nanos. Returns {p50, p95, p99, max} in microseconds. */
    public static double[] percentiles(long[] nanos) {
        java.util.Arrays.sort(nanos);
        int n = nanos.length;
        return new double[]{
                nanos[(int) (n * 0.50)] / 1000.0,
                nanos[(int) (n * 0.95)] / 1000.0,
                nanos[(int) (n * 0.99)] / 1000.0,
                nanos[n - 1] / 1000.0};
    }
}
