package me.alex4386.typhon.engine.volcano;

/**
 * A one-word description of the conduit's current steady flow, derived from it for telemetry and
 * dashboards. It never drives behaviour: the surface partition and every downstream subsystem use the
 * flow's continuous quantities (exit velocity, gas fractions, fragmentation, viscosity), and the
 * eruption style is estimated from what actually happens at the surface.
 */
public enum EruptiveRegime {
    /** The magma model does not resolve conduit flow (e.g. a test stub). */
    UNKNOWN,
    /** Not erupting. */
    QUIESCENT,
    /** Fluid magma tearing into clots at or near the vent: lava fountains. */
    FOUNTAINING,
    /** Coherent fluid magma whose gas segregates into slugs. */
    OPEN_VENT,
    /** Coherent, fluid-to-intermediate lava leaving the vent. */
    EFFUSIVE,
    /** Coherent, outgassed, very viscous lava. */
    DOME,
    /** Magma fragmenting inside the conduit: a gas–pyroclast jet. */
    EXPLOSIVE
}
