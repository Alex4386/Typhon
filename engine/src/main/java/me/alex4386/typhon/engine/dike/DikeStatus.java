package me.alex4386.typhon.engine.dike;

public enum DikeStatus {
    /** Tip still rising. */
    PROPAGATING,
    /** Stopped underground: insufficient pressure or the magma froze. The intrusion remains. */
    STALLED,
    /** Reached the surface and opened a fissure. */
    ERUPTED
}
