package me.alex4386.typhon.engine.volcano;

/** Lifecycle state of a vent (summit crater or dike-fed fissure). */
public enum VentStatus {
    /** Open and not erupting (a summit crater between eruptions). */
    IDLE,
    /** Erupting, and its feeder is holding open or widening. */
    ACTIVE,
    /** Erupting, but its feeder is narrowing: heat lost to the wall rock outpaces the heat the flow brings. */
    WANING,
    /** The feeder froze shut: the dike is now an intrusion and the vent an extinct landform. */
    FROZEN,
    /** Plugged by the user: no magma leaves through it until unsealed. */
    SEALED,
    /** Deleted by the user: no longer part of the volcano's vent set. */
    REMOVED
}
