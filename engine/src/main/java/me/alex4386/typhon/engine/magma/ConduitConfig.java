package me.alex4386.typhon.engine.magma;

/**
 * Physical parameters of the conduit between a {@link MagmaChamber} and its vent, used by the steady
 * conduit-flow model ({@link me.alex4386.typhon.engine.magma.conduit.ConduitModel}). None of them
 * selects an eruption style: fountains, slugs, plugs, domes and explosive columns all follow from
 * the flow these parameters produce (see {@code docs/eruption-dynamics.md}).
 *
 * @param initialOpenness molten share of the conduit's radius at the start: 0 = no conduit (a volcano that
 *     has not erupted yet, or one whose conduit froze solid; magma must break out through a dike), 1 = a
 *     fully molten conduit (a persistently active vent). In repose it falls as the conduit solidifies (see
 *     {@link MagmaChamber#conduitFreezeSeconds})
 * @param fragmentationPorosity gas volume fraction at which an expanding foam breaks up (~0.75;
 *     Sparks 1978)
 * @param brittleStressPa melt viscosity × strain rate above which melt fails brittlely
 *     ({@code k·G∞}, k ≈ 0.01, G∞ ≈ 10 GPa; Papale 1999)
 * @param foamStrengthPa tensile strength of bubble walls: a viscous foam past the fragmentation
 *     porosity breaks once its bubbles' viscous overpressure ({@code 4/3 η ε̇}) exceeds this over
 *     the porosity (Spieler et al. 2004); slowly rising foam relaxes and stays coherent
 * @param turbulentFrictionFactor Darcy friction factor of the gas–pyroclast flow above
 *     fragmentation (~0.02; Wilson &amp; Head 1981)
 * @param referencePermeability permeability (m²) of a fully connected bubble network; it scales as
 *     the cube of the gas fraction above percolation (10⁻¹⁴–10⁻¹¹ m²; Klug &amp; Cashman 1996)
 * @param percolationThreshold gas volume fraction at which bubbles connect (~0.3)
 * @param wallPermeability permeability (m²) of the conduit wall rock for lateral outgassing
 * @param gasViscosity dynamic viscosity of magmatic water vapour (Pa·s)
 * @param microlitesPerWtWater equilibrium microlite fraction gained per wt% of water lost on ascent
 *     (decompression crystallisation; Cashman &amp; Blundy 2000)
 * @param crystallisationTimescale kinetic time (physical s) to approach that equilibrium
 * @param maxCrystalFraction cap on total crystals (near maximum packing)
 * @param bubbleRadiusM bubble radius for the capillary number of the bubbly rheology
 * @param surfaceTension melt–gas surface tension (N/m)
 * @param coalescenceViscosity melt viscosity (Pa·s) at which bubble coalescence into slugs is
 *     halved; coalescence fades in viscous melt
 * @param slugLengthDiameters mean length of a gas slug in conduit diameters (James et al. 2008)
 * @param plugViscosityLog10 log10 exit viscosity (Pa·s) at which the uppermost conduit stiffens
 *     into a plug that traps gas (degassing-induced crystallisation; Diller et al. 2006)
 * @param plugStrengthMPa strength a fully stiffened plug resists trapped gas with
 * @param plugCapDepthM depth of the porous zone beneath the plug in which gas accumulates
 * @param plugPorosity connected porosity of that zone
 * @param exsolutionTimescale diffusive time (physical s) for dissolved volatiles to reach
 *     solubility equilibrium as pressure falls (bubble growth; Sparks 1978). The flow chokes at its
 *     frozen (not equilibrium) sound speed.
 * @param wallSlipStressPa shear stress at which coherent magma stops deforming viscously at the wall
 *     and slides on a marginal shear zone (Pa; dome plugs ~0.5–5 MPa, Iverson et al. 2006). Caps
 *     the wall friction of stiff, crystal-rich magma
 * @param wallFrictionCoefficient friction coefficient of that shear zone on the magma pressure
 *     (gouge, ~0.1–0.6): the slip stress grows with depth
 * @param gridSteps integration steps along the conduit
 */
public record ConduitConfig(
        double initialOpenness,
        double fragmentationPorosity,
        double brittleStressPa,
        double foamStrengthPa,
        double turbulentFrictionFactor,
        double referencePermeability,
        double percolationThreshold,
        double wallPermeability,
        double gasViscosity,
        double microlitesPerWtWater,
        double crystallisationTimescale,
        double maxCrystalFraction,
        double bubbleRadiusM,
        double surfaceTension,
        double coalescenceViscosity,
        double slugLengthDiameters,
        double plugViscosityLog10,
        double plugStrengthMPa,
        double plugCapDepthM,
        double plugPorosity,
        double exsolutionTimescale,
        double wallSlipStressPa,
        double wallFrictionCoefficient,
        int gridSteps) {

    /** Literature-based defaults; see each parameter for its source range. */
    public static final ConduitConfig DEFAULT = new ConduitConfig(
            0,
            0.75, 1e8, 1e6, 0.02,
            1e-11, 0.3, 1e-13, 3e-5,
            0.12, 2e4, 0.6,
            1e-4, 0.1,
            1e3, 5,
            10, 5, 500, 0.2,
            1.0, 1e6, 0.1,
            160);

    public ConduitConfig {
        if (!(initialOpenness >= 0 && initialOpenness <= 1)) throw new IllegalArgumentException("initialOpenness must be in [0, 1]");
        requireFraction("fragmentationPorosity", fragmentationPorosity);
        requirePositive("brittleStressPa", brittleStressPa);
        requirePositive("foamStrengthPa", foamStrengthPa);
        requirePositive("turbulentFrictionFactor", turbulentFrictionFactor);
        requirePositive("referencePermeability", referencePermeability);
        if (!(percolationThreshold >= 0 && percolationThreshold < fragmentationPorosity)) {
            throw new IllegalArgumentException("percolationThreshold must be in [0, fragmentationPorosity)");
        }
        requirePositive("wallPermeability", wallPermeability);
        requirePositive("gasViscosity", gasViscosity);
        if (!(microlitesPerWtWater >= 0)) throw new IllegalArgumentException("microlitesPerWtWater must be >= 0");
        requirePositive("crystallisationTimescale", crystallisationTimescale);
        requireFraction("maxCrystalFraction", maxCrystalFraction);
        requirePositive("bubbleRadiusM", bubbleRadiusM);
        requirePositive("surfaceTension", surfaceTension);
        requirePositive("coalescenceViscosity", coalescenceViscosity);
        requirePositive("slugLengthDiameters", slugLengthDiameters);
        requirePositive("plugViscosityLog10", plugViscosityLog10);
        requirePositive("plugStrengthMPa", plugStrengthMPa);
        requirePositive("plugCapDepthM", plugCapDepthM);
        requireFraction("plugPorosity", plugPorosity);
        requirePositive("exsolutionTimescale", exsolutionTimescale);
        requirePositive("wallSlipStressPa", wallSlipStressPa);
        if (!(wallFrictionCoefficient >= 0)) throw new IllegalArgumentException("wallFrictionCoefficient must be >= 0");
        if (gridSteps < 20) throw new IllegalArgumentException("gridSteps must be >= 20");
    }

    public ConduitConfig withInitialOpenness(double value) {
        return new ConduitConfig(value, fragmentationPorosity, brittleStressPa, foamStrengthPa,
                turbulentFrictionFactor, referencePermeability, percolationThreshold, wallPermeability, gasViscosity,
                microlitesPerWtWater, crystallisationTimescale, maxCrystalFraction, bubbleRadiusM, surfaceTension,
                coalescenceViscosity, slugLengthDiameters, plugViscosityLog10, plugStrengthMPa, plugCapDepthM, plugPorosity,
                exsolutionTimescale, wallSlipStressPa, wallFrictionCoefficient, gridSteps);
    }

    public ConduitConfig withPermeability(double reference, double wall) {
        return new ConduitConfig(initialOpenness, fragmentationPorosity,
                brittleStressPa, foamStrengthPa, turbulentFrictionFactor, reference, percolationThreshold, wall, gasViscosity,
                microlitesPerWtWater, crystallisationTimescale, maxCrystalFraction, bubbleRadiusM, surfaceTension,
                coalescenceViscosity, slugLengthDiameters, plugViscosityLog10, plugStrengthMPa, plugCapDepthM, plugPorosity,
                exsolutionTimescale, wallSlipStressPa, wallFrictionCoefficient, gridSteps);
    }

    public ConduitConfig withPlug(double viscosityLog10, double strengthMPa) {
        return new ConduitConfig(initialOpenness, fragmentationPorosity,
                brittleStressPa, foamStrengthPa, turbulentFrictionFactor, referencePermeability, percolationThreshold, wallPermeability,
                gasViscosity, microlitesPerWtWater, crystallisationTimescale, maxCrystalFraction, bubbleRadiusM,
                surfaceTension, coalescenceViscosity, slugLengthDiameters, viscosityLog10, strengthMPa, plugCapDepthM,
                plugPorosity, exsolutionTimescale, wallSlipStressPa, wallFrictionCoefficient, gridSteps);
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be positive: " + value);
    }

    private static void requireFraction(String name, double value) {
        if (!(value > 0 && value <= 1)) throw new IllegalArgumentException(name + " must be in (0, 1]: " + value);
    }
}
