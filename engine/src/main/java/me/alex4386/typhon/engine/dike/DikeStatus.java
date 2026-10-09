package me.alex4386.typhon.engine.dike;

public enum DikeStatus {
    /** Tip still rising. */
    PROPAGATING,
    /**
     * Stopped underground (too little pressure, or too slow to outrun freezing). Molten until its sheet
     * solidifies ({@link Dike#molten()}): renewed pressure can drive it on until then. Afterwards an intrusion.
     */
    STALLED,
    /** Reached the surface and opened a fissure. */
    ERUPTED
}
