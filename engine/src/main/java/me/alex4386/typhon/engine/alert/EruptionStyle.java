package me.alex4386.typhon.engine.alert;

/** Eruption styles, from effusive to most explosive (plus dome extrusion). */
public enum EruptionStyle {
    /** Fluid, gas-poor basalt: lava fountains and long flows. */
    HAWAIIAN,
    /** Fluid to intermediate magma with gas slugs: rhythmic bursts of spatter and bombs. */
    STROMBOLIAN,
    /** Viscous magma under a plug: short, violent cannon-like explosions. */
    VULCANIAN,
    /** Viscous, gas-rich dome magma: dome collapse and pyroclastic flows. */
    PELEAN,
    /** Viscous, very gas-rich magma at high rate: sustained eruption column. */
    PLINIAN,
    /** Viscous, degassed magma: slow extrusion of a lava dome. */
    LAVA_DOME
}
