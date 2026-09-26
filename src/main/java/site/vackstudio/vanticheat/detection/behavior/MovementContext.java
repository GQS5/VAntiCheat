package site.vackstudio.vanticheat.detection.behavior;

public record MovementContext(boolean fluid, boolean climbable, boolean slime, boolean ice,
                              boolean vehicle, boolean gliding, boolean specialGameMode,
                              boolean activeEffect, boolean externalVelocity,
                              boolean teleportReset, boolean uncertain,
                              boolean safeLanding, boolean fallDamageReducing,
                              String landingSurface, boolean sprinting,
                              boolean speedEffect, boolean slownessEffect,
                              boolean positionCorrection, boolean timingUncertain) {
    public MovementContext(boolean fluid, boolean climbable, boolean slime, boolean ice,
                           boolean vehicle, boolean gliding, boolean specialGameMode,
                           boolean activeEffect, boolean externalVelocity,
                           boolean teleportReset, boolean uncertain) {
        this(fluid, climbable, slime, ice, vehicle, gliding, specialGameMode, activeEffect,
                externalVelocity, teleportReset, uncertain, false, false, "UNKNOWN",
                false, false, false, false, false);
    }

    public MovementContext(boolean fluid, boolean climbable, boolean slime, boolean ice,
                           boolean vehicle, boolean gliding, boolean specialGameMode,
                           boolean activeEffect, boolean externalVelocity,
                           boolean teleportReset, boolean uncertain,
                           boolean safeLanding, boolean fallDamageReducing,
                           String landingSurface) {
        this(fluid, climbable, slime, ice, vehicle, gliding, specialGameMode, activeEffect,
                externalVelocity, teleportReset, uncertain, safeLanding, fallDamageReducing,
                landingSurface, false, false, false, false, false);
    }

    public static MovementContext normal() {
        return new MovementContext(false, false, false, false, false, false, false,
                false, false, false, false, false, false, "UNKNOWN", false,
                false, false, false, false);
    }

    public boolean specialMovement() {
        return fluid || climbable || slime || ice || vehicle || gliding || specialGameMode
                || activeEffect || externalVelocity || teleportReset;
    }

    public MovementContext withTimingUncertain(boolean value) {
        return new MovementContext(fluid, climbable, slime, ice, vehicle, gliding, specialGameMode,
                activeEffect, externalVelocity, teleportReset, uncertain, safeLanding,
                fallDamageReducing, landingSurface, sprinting, speedEffect, slownessEffect,
                positionCorrection, value);
    }
}
