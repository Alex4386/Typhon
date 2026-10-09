package me.alex4386.typhon.engine.dike;


/**
 * Parameters of dike initiation and propagation, in SI units (lengths in metres). The dike's geometry
 * (breadth, opening, fissure length and width) is derived from elastic crack mechanics and the chamber
 * feeding it (see {@link DikePropagation}); what is set here are material properties of the crust, the
 * statistical description of path wander, and numerical safeguards (labelled as such).
 */
public final class DikeConfig {
    /** Seconds between steps. */
    public double stepPeriodSeconds = DikePropagation.RISING_STEP_SECONDS;

    // ── Initiation ──

    public int maxConcurrentDikes = 1;
    /**
     * The one dike override: no new dikes from this chamber where wall rupture would open them (rising dikes
     * go on; forced ones still start). Off (default): the physics decides — wall rupture opens a dike at once.
     */
    public boolean blocked = false;

    // ── Mechanics ──

    /**
     * Effective shear modulus of the volcanic crust (Pa): edifice and shallow-crust rocks are fractured, with
     * effective moduli of a few GPa, well below laboratory values (Heap et al. 2020, J. Volcanol. Geotherm.
     * Res. 390; Rubin 1995).
     */
    public double shearModulusPa = 3e9;
    public double poissonRatio = 0.25;
    /**
     * Effective fracture toughness of the host rock (MPa·√m). Laboratory values are ~1–3 MPa·√m; dike
     * dimensions suggest larger in-situ values (up to ~100; Delaney &amp; Pollard 1981, Rubin 1995). The dike
     * stalls when the stress intensity at its tip, {@code K = ΔP·√(π b/2)}, falls below this.
     */
    public double fractureToughnessMPaSqrtM = 10;
    /** Thermal diffusivity of the wall rock (m²/s; typical crustal rock, Turcotte &amp; Schubert 2002). */
    public double wallRockDiffusivity = 1e-6;
    /** Latent heat of crystallisation of the magma (J/kg; basaltic ~4e5, Turcotte &amp; Schubert 2002). */
    public double magmaLatentHeat = 4e5;
    /** Specific heat of magma and rock (J/kg/K). */
    public double specificHeat = 1200;
    /**
     * Geothermal gradient of the crust the dike crosses (°C/km) and surface temperature (°C), for the wall
     * rock temperature that freezes the magma (continental average ~25–30 °C/km; volcanic areas are hotter).
     */
    public double geothermalGradientCPerKm = 30;
    public double surfaceTemperatureC = 10;
    /** Numerical: lower bound on the height used for the pressure gradient (m), avoids singular starts. */
    public double minCharacteristicHeight = 200;
    /** Numerical: longest advance integrated in one sub-step (m). */
    public double maxSubstepMeters = 50;
    /**
     * Azimuth (degrees clockwise from +x towards +z) of the regional least compressive stress σ₃, or NaN for
     * none. Where neither the chamber nor the topography sets a direction (a dike rising straight above the
     * chamber on flat ground), a dike strikes perpendicular to σ₃ (Anderson 1951); without a regional stress
     * the strike there is undetermined and drawn at random.
     */
    public double regionalSigma3AzimuthDeg = Double.NaN;

    // ── Path ──

    /**
     * Strength of deflection down the edifice slope near the surface (dimensionless). Edifice loading turns
     * shallow dikes towards the flanks (Muller et al. 2001, J. Geophys. Res. 106; Acocella &amp; Neri 2009);
     * the strength and depth scale here parametrise that effect (not computed from the load's stress field).
     */
    public double deflectionStrength = 3.0;
    /** Depth over which edifice stresses fade (m); deflection ∝ exp(−depth / scale). */
    public double edificeDepthScale = 1500;
    /** Half-width of the slope stencil on the terrain (m). */
    public double slopeSampleRadiusM = 200;
    /**
     * Stationary standard deviation of the random heading perturbation (dimensionless slope): host-rock
     * heterogeneity (layering, old fractures) the model does not resolve. An Ornstein–Uhlenbeck process
     * along the path, so dikes wander but stay roughly vertical. Statistical description, not derived.
     */
    public double headingNoise = 0.1;
    /** Correlation length of the heading perturbation along the path (m). */
    public double headingCorrelationLength = 500;

    // ── Seismicity and output ──

    /**
     * Mean number of VT hypocentres per km of tip advance (above the catalogue's minimum magnitude). An
     * observational rate, of the order of the swarms that track propagating dikes (e.g. Sigmundsson et al.
     * 2015, Nature 517); not derived. Hypocentres are placed along the dike's leading edge.
     */
    public double hypocentersPerKm = 25;
    /** Numerical: finished dikes kept for queries and deformation (oldest are dropped first). */
    public int maxRecordedDikes = 16;

    public static DikeConfig defaults() {
        return new DikeConfig();
    }

    public DikeConfig copy() {
        DikeConfig c = new DikeConfig();
        c.stepPeriodSeconds = stepPeriodSeconds;
        c.maxConcurrentDikes = maxConcurrentDikes;
        c.blocked = blocked;
        c.shearModulusPa = shearModulusPa;
        c.poissonRatio = poissonRatio;
        c.minCharacteristicHeight = minCharacteristicHeight;
        c.fractureToughnessMPaSqrtM = fractureToughnessMPaSqrtM;
        c.wallRockDiffusivity = wallRockDiffusivity;
        c.magmaLatentHeat = magmaLatentHeat;
        c.specificHeat = specificHeat;
        c.geothermalGradientCPerKm = geothermalGradientCPerKm;
        c.surfaceTemperatureC = surfaceTemperatureC;
        c.regionalSigma3AzimuthDeg = regionalSigma3AzimuthDeg;
        c.maxSubstepMeters = maxSubstepMeters;
        c.deflectionStrength = deflectionStrength;
        c.edificeDepthScale = edificeDepthScale;
        c.slopeSampleRadiusM = slopeSampleRadiusM;
        c.headingNoise = headingNoise;
        c.headingCorrelationLength = headingCorrelationLength;
        c.hypocentersPerKm = hypocentersPerKm;
        c.maxRecordedDikes = maxRecordedDikes;
        return c;
    }

    public void validate() {
        requirePositive("stepPeriodSeconds", stepPeriodSeconds);
        if (maxConcurrentDikes < 0) throw new IllegalArgumentException("maxConcurrentDikes must be >= 0");
        requirePositive("shearModulusPa", shearModulusPa);
        if (!(poissonRatio > 0 && poissonRatio < 0.5)) {
            throw new IllegalArgumentException("poissonRatio must be in (0, 0.5)");
        }
        requirePositive("fractureToughnessMPaSqrtM", fractureToughnessMPaSqrtM);
        requirePositive("wallRockDiffusivity", wallRockDiffusivity);
        requirePositive("magmaLatentHeat", magmaLatentHeat);
        requirePositive("specificHeat", specificHeat);
        if (!(geothermalGradientCPerKm >= 0)) throw new IllegalArgumentException("geothermalGradientCPerKm must be >= 0");
        requirePositive("minCharacteristicHeight", minCharacteristicHeight);
        requirePositive("maxSubstepMeters", maxSubstepMeters);
        requirePositive("edificeDepthScale", edificeDepthScale);
        requirePositive("headingCorrelationLength", headingCorrelationLength);
        requirePositive("slopeSampleRadiusM", slopeSampleRadiusM);
        if (maxRecordedDikes < 1) throw new IllegalArgumentException("maxRecordedDikes must be >= 1");
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be > 0: " + value);
    }
}
