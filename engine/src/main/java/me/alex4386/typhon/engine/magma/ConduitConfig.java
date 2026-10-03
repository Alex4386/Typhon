package me.alex4386.typhon.engine.magma;

/**
 * Conduit-flow parameters of a {@link MagmaChamber}: how magma degasses on its way up, when it
 * fragments, and how gas drives discrete Strombolian and Vulcanian explosions.
 *
 * @param permeableOutgassingTimescale e-folding time (s) for gas to escape through connected
 *     bubbles and conduit walls during ascent. Slow ascent (≫ this) erupts degassed magma.
 * @param fragmentationGasFraction gas volume fraction at which a bubbly melt fragments (~0.75;
 *     Sparks 1978)
 * @param fragmentationPressureMPa pressure at the fragmentation level where the gas fraction is
 *     evaluated; magma that only reaches it shallower than this erupts as degassing lava
 * @param brittleStressPa melt viscosity × strain rate above which melt fails brittlely
 *     ({@code k·G∞} with k ≈ 0.01, G∞ ≈ 10 GPa; Papale 1999)
 * @param degassedWaterWt H₂O left in fully degassed lava at the surface (wt%)
 * @param slugFlowMaxViscosity bulk viscosity (Pa·s) above which bubbles cannot coalesce into
 *     conduit-filling gas slugs (no Strombolian activity in viscous magma)
 * @param reopenOverpressureMPa overpressure that re-opens a conduit left open by a recent eruption
 *     (an open conduit does not need to fracture the roof again)
 * @param conduitSealTimescale e-folding time (physical s) for an open conduit to seal after an
 *     eruption; long repose returns the system to closed-conduit, tensile-strength failure
 * @param initialOpenness 0 = sealed (fresh failure needed), 1 = open (persistently active vent)
 * @param slugGasFraction share of the exsolving gas flux that gathers into slugs (the rest
 *     escapes passively)
 * @param slugGasMassKg mean gas mass of one Strombolian slug (Stromboli: ~10–1000 kg)
 * @param slugOverpressureMPa overpressure of a bursting slug (~0.1–0.5 MPa)
 * @param strombolianGasMassFraction gas mass fraction of a Strombolian burst's ejecta + gas
 *     (pyroclasts ~10–100× the gas mass)
 * @param strombolianDurationSeconds duration of one Strombolian burst
 * @param plugTrappedGasFraction share of the exsolving gas flux trapped beneath a dome plug
 * @param plugCapDepthM depth of the porous upper conduit in which trapped gas accumulates
 * @param plugPorosity connected porosity of that zone
 * @param plugStrengthMPa gas pressure at which the plug fails in a Vulcanian explosion
 * @param plugResealSeconds time (physical s) for a new plug to seal after an explosion
 * @param vulcanianGasMassFraction gas mass fraction of the fragmented charge (~1–3 wt%)
 * @param vulcanianDurationSeconds duration of one Vulcanian explosion
 */
public record ConduitConfig(
        double permeableOutgassingTimescale,
        double fragmentationGasFraction,
        double fragmentationPressureMPa,
        double brittleStressPa,
        double degassedWaterWt,
        double slugFlowMaxViscosity,
        double reopenOverpressureMPa,
        double conduitSealTimescale,
        double initialOpenness,
        double slugGasFraction,
        double slugGasMassKg,
        double slugOverpressureMPa,
        double strombolianGasMassFraction,
        double strombolianDurationSeconds,
        double plugTrappedGasFraction,
        double plugCapDepthM,
        double plugPorosity,
        double plugStrengthMPa,
        double plugResealSeconds,
        double vulcanianGasMassFraction,
        double vulcanianDurationSeconds) {

    /** Literature-based defaults; see each parameter for its source range. */
    public static final ConduitConfig DEFAULT = new ConduitConfig(
            3600, 0.75, 1.0, 1e8, 0.1, 1e4,
            3.0, 3e7, 0,
            0.03, 250, 0.2, 0.01, 10,
            0.3, 500, 0.2, 5, 1800, 0.02, 40);

    public ConduitConfig {
        requirePositive("permeableOutgassingTimescale", permeableOutgassingTimescale);
        if (!(fragmentationGasFraction > 0 && fragmentationGasFraction < 1)) {
            throw new IllegalArgumentException("fragmentationGasFraction must be in (0, 1)");
        }
        requirePositive("fragmentationPressureMPa", fragmentationPressureMPa);
        requirePositive("brittleStressPa", brittleStressPa);
        if (degassedWaterWt < 0) throw new IllegalArgumentException("degassedWaterWt must be >= 0");
        requirePositive("slugFlowMaxViscosity", slugFlowMaxViscosity);
        requirePositive("reopenOverpressureMPa", reopenOverpressureMPa);
        requirePositive("conduitSealTimescale", conduitSealTimescale);
        if (!(initialOpenness >= 0 && initialOpenness <= 1)) throw new IllegalArgumentException("initialOpenness must be in [0, 1]");
        requireFraction("slugGasFraction", slugGasFraction);
        requirePositive("slugGasMassKg", slugGasMassKg);
        requirePositive("slugOverpressureMPa", slugOverpressureMPa);
        requireFraction("strombolianGasMassFraction", strombolianGasMassFraction);
        requirePositive("strombolianDurationSeconds", strombolianDurationSeconds);
        requireFraction("plugTrappedGasFraction", plugTrappedGasFraction);
        requirePositive("plugCapDepthM", plugCapDepthM);
        requireFraction("plugPorosity", plugPorosity);
        requirePositive("plugStrengthMPa", plugStrengthMPa);
        if (plugResealSeconds < 0) throw new IllegalArgumentException("plugResealSeconds must be >= 0");
        requireFraction("vulcanianGasMassFraction", vulcanianGasMassFraction);
        requirePositive("vulcanianDurationSeconds", vulcanianDurationSeconds);
    }

    public ConduitConfig withInitialOpenness(double value) {
        return new ConduitConfig(permeableOutgassingTimescale, fragmentationGasFraction, fragmentationPressureMPa,
                brittleStressPa, degassedWaterWt, slugFlowMaxViscosity, reopenOverpressureMPa, conduitSealTimescale,
                value, slugGasFraction, slugGasMassKg, slugOverpressureMPa, strombolianGasMassFraction,
                strombolianDurationSeconds, plugTrappedGasFraction, plugCapDepthM, plugPorosity, plugStrengthMPa,
                plugResealSeconds, vulcanianGasMassFraction, vulcanianDurationSeconds);
    }

    public ConduitConfig withPermeableOutgassingTimescale(double value) {
        return new ConduitConfig(value, fragmentationGasFraction, fragmentationPressureMPa,
                brittleStressPa, degassedWaterWt, slugFlowMaxViscosity, reopenOverpressureMPa, conduitSealTimescale,
                initialOpenness, slugGasFraction, slugGasMassKg, slugOverpressureMPa, strombolianGasMassFraction,
                strombolianDurationSeconds, plugTrappedGasFraction, plugCapDepthM, plugPorosity, plugStrengthMPa,
                plugResealSeconds, vulcanianGasMassFraction, vulcanianDurationSeconds);
    }

    public ConduitConfig withReopenOverpressureMPa(double value) {
        return new ConduitConfig(permeableOutgassingTimescale, fragmentationGasFraction, fragmentationPressureMPa,
                brittleStressPa, degassedWaterWt, slugFlowMaxViscosity, value, conduitSealTimescale,
                initialOpenness, slugGasFraction, slugGasMassKg, slugOverpressureMPa, strombolianGasMassFraction,
                strombolianDurationSeconds, plugTrappedGasFraction, plugCapDepthM, plugPorosity, plugStrengthMPa,
                plugResealSeconds, vulcanianGasMassFraction, vulcanianDurationSeconds);
    }

    public ConduitConfig withSlug(double gasFraction, double meanGasMassKg) {
        return new ConduitConfig(permeableOutgassingTimescale, fragmentationGasFraction, fragmentationPressureMPa,
                brittleStressPa, degassedWaterWt, slugFlowMaxViscosity, reopenOverpressureMPa, conduitSealTimescale,
                initialOpenness, gasFraction, meanGasMassKg, slugOverpressureMPa, strombolianGasMassFraction,
                strombolianDurationSeconds, plugTrappedGasFraction, plugCapDepthM, plugPorosity, plugStrengthMPa,
                plugResealSeconds, vulcanianGasMassFraction, vulcanianDurationSeconds);
    }

    public ConduitConfig withPlug(double strengthMPa, double resealSeconds) {
        return new ConduitConfig(permeableOutgassingTimescale, fragmentationGasFraction, fragmentationPressureMPa,
                brittleStressPa, degassedWaterWt, slugFlowMaxViscosity, reopenOverpressureMPa, conduitSealTimescale,
                initialOpenness, slugGasFraction, slugGasMassKg, slugOverpressureMPa, strombolianGasMassFraction,
                strombolianDurationSeconds, plugTrappedGasFraction, plugCapDepthM, plugPorosity, strengthMPa,
                resealSeconds, vulcanianGasMassFraction, vulcanianDurationSeconds);
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be positive: " + value);
    }

    private static void requireFraction(String name, double value) {
        if (!(value > 0 && value <= 1)) throw new IllegalArgumentException(name + " must be in (0, 1]: " + value);
    }
}
