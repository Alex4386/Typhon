package me.alex4386.typhon.engine.volcano;

/**
 * How magma leaves the conduit, decided by conduit physics (ascent speed, gas segregation,
 * permeable outgassing, brittle fragmentation) rather than by composition alone.
 */
public enum EruptiveRegime {
    /** The magma model does not resolve conduit flow (e.g. a test stub). */
    UNKNOWN,
    /** Not erupting. */
    QUIESCENT,
    /** Fluid magma rising faster than its gas can separate: gas-driven lava fountains (Hawaiian). */
    FOUNTAINING,
    /** Fluid magma rising slowly enough for gas slugs to segregate: open vent, Strombolian bursts. */
    OPEN_VENT,
    /** Viscous, outgassed magma extruding slowly under a plug: lava dome, Vulcanian plug failures. */
    DOME,
    /** Gas-rich magma fragmenting brittlely in the conduit: sustained explosive column. */
    EXPLOSIVE
}
