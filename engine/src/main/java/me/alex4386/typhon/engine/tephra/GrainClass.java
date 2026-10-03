package me.alex4386.typhon.engine.tephra;

/**
 * Grain-size classes of airborne tephra with their default terminal settling velocities.
 *
 * <p>The velocities are representative of real particles in order of magnitude and deliberately on
 * the fast side for fine ash so fallout stays within a few hundred to a thousand blocks of the vent.
 * They can be overridden in {@link TephraConfig}.
 */
public enum GrainClass {
    /** 2–64 mm. Falls out close to the vent. */
    LAPILLI(8.0),
    /** 0.5–2 mm. */
    COARSE_ASH(3.0),
    /** 0.063–0.5 mm. */
    MEDIUM_ASH(1.2),
    /** Below 63 µm. Travels farthest; much of it leaves the simulated domain. */
    FINE_ASH(0.4);

    public static final int COUNT = values().length;

    private final double defaultSettlingVelocity;

    GrainClass(double defaultSettlingVelocity) {
        this.defaultSettlingVelocity = defaultSettlingVelocity;
    }

    /** Terminal settling velocity in m/s. */
    public double defaultSettlingVelocity() {
        return defaultSettlingVelocity;
    }
}
