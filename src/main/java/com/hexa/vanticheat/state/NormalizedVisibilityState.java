package com.hexa.vanticheat.state;

/** Compact immutable visibility view (§8/§26). */
public record NormalizedVisibilityState(double distance, boolean lineOfSight,
                                        boolean obstructed, long windowMs) {}
