package me.alex4386.typhon.engine.tephra;

/**
 * Grain-size classes of airborne tephra with their default terminal settling velocities.
 *
 * <p>Velocities of a representative particle of each class (density ≈ 2500 kg/m³ for ash, less for
 * vesicular lapilli) in air: fine ash follows Stokes' law ({@code v = ρ g d² / 18μ}, ≈ 0.07 m/s at 30 µm),
 * medium ash is transitional (≈ 1 m/s at 0.2 mm), coarse ash and lapilli fall in the drag-dominated regime
 * (Bonadonna et al. 1998, J. Volcanol. Geotherm. Res. 81:173-187). They can be overridden in
 * {@link TephraConfig}.
 */
public enum GrainClass {
    /** 2–64 mm. Falls out close to the vent. */
    LAPILLI(8.0),
    /** 0.5–2 mm. */
    COARSE_ASH(3.0),
    /** 0.063–0.5 mm. */
    MEDIUM_ASH(1.0),
    /** Below 63 µm. Travels farthest; much of it leaves the simulated domain. */
    FINE_ASH(0.07);

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
