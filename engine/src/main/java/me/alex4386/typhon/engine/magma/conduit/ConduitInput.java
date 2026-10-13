package me.alex4386.typhon.engine.magma.conduit;

/**
 * State at the bottom and top of the conduit for one steady solution.
 *
 * @param chamberPressurePa absolute pressure at the conduit inlet (lithostatic + overpressure)
 * @param temperatureC magma temperature (the flow is treated as isothermal)
 * @param silicaWt SiO₂ of the melt (wt%)
 * @param dissolvedWaterWt H₂O dissolved in the melt at the inlet (wt%)
 * @param co2Wt total CO₂ of the melt (wt%); whatever exceeds solubility at the inlet pressure
 *     enters the conduit as free gas
 * @param exsolvedGasMassFraction free gas already exsolved in the chamber (mass fraction of magma)
 * @param crystalFraction crystal volume fraction carried from the chamber
 * @param radiusM conduit radius
 * @param lengthM conduit length (chamber depth)
 * @param ventAmbientPressurePa pressure the flow exits into: atmosphere plus any water above the vent
 * @param waterTableDepthM depth of the water table below the vent (0 when the wall rock is
 *     saturated to the vent, as under the sea); pore pressure in the wall is ambient above it and
 *     hydrostatic below, which sets how much gas the conduit can lose laterally
 * @param lithostaticPressurePa lithostatic stress at the conduit inlet (Pa): with the vent's ambient pressure it
 *     sets the stress in the wall rock along the conduit ({@code NaN}: the walls are not examined)
 * @param shape radius along the conduit, {@code null} for a cylinder of {@code radiusM}; {@code radiusM} stays the
 *     reference that mass flux per area is given against
 */
public record ConduitInput(
        double chamberPressurePa,
        double temperatureC,
        double silicaWt,
        double dissolvedWaterWt,
        double co2Wt,
        double exsolvedGasMassFraction,
        double crystalFraction,
        double radiusM,
        double lengthM,
        double ventAmbientPressurePa,
        double waterTableDepthM,
        double lithostaticPressurePa,
        ConduitShape shape) {

    public static final double ATMOSPHERE_PA = 101_325;

    /** Without the lithostatic stress: the conduit walls are not examined. */
    public ConduitInput(double chamberPressurePa, double temperatureC, double silicaWt, double dissolvedWaterWt,
            double co2Wt, double exsolvedGasMassFraction, double crystalFraction, double radiusM, double lengthM,
            double ventAmbientPressurePa, double waterTableDepthM) {
        this(chamberPressurePa, temperatureC, silicaWt, dissolvedWaterWt, co2Wt, exsolvedGasMassFraction,
                crystalFraction, radiusM, lengthM, ventAmbientPressurePa, waterTableDepthM, Double.NaN, null);
    }

    /** A cylindrical conduit. */
    public ConduitInput(double chamberPressurePa, double temperatureC, double silicaWt, double dissolvedWaterWt,
            double co2Wt, double exsolvedGasMassFraction, double crystalFraction, double radiusM, double lengthM,
            double ventAmbientPressurePa, double waterTableDepthM, double lithostaticPressurePa) {
        this(chamberPressurePa, temperatureC, silicaWt, dissolvedWaterWt, co2Wt, exsolvedGasMassFraction,
                crystalFraction, radiusM, lengthM, ventAmbientPressurePa, waterTableDepthM, lithostaticPressurePa, null);
    }

    /** Radius (m) at {@code fraction} of the length up from the inlet. */
    public double radiusAt(double fraction) {
        return shape == null ? radiusM : shape.radiusAt(fraction);
    }

    public ConduitInput {
        if (!(chamberPressurePa > 0)) throw new IllegalArgumentException("chamber pressure must be positive");
        if (!(radiusM > 0) || !(lengthM > 0)) throw new IllegalArgumentException("conduit geometry must be positive");
        if (!(ventAmbientPressurePa > 0)) throw new IllegalArgumentException("ambient pressure must be positive");
    }

    /** The same drivers at another chamber pressure. */
    public ConduitInput withChamberPressurePa(double pressurePa) {
        return new ConduitInput(pressurePa, temperatureC, silicaWt, dissolvedWaterWt, co2Wt, exsolvedGasMassFraction,
                crystalFraction, radiusM, lengthM, ventAmbientPressurePa, waterTableDepthM, lithostaticPressurePa, shape);
    }

    /** The same drivers through a conduit of another shape. */
    public ConduitInput withShape(ConduitShape s) {
        return new ConduitInput(chamberPressurePa, temperatureC, silicaWt, dissolvedWaterWt, co2Wt, exsolvedGasMassFraction,
                crystalFraction, radiusM, lengthM, ventAmbientPressurePa, waterTableDepthM, lithostaticPressurePa, s);
    }

    /** True when the two inputs differ by less than {@code tolerance} (relative) in every driver. */
    public boolean closeTo(ConduitInput o, double tolerance) {
        if (o == null) return false;
        return rel(chamberPressurePa, o.chamberPressurePa) < tolerance * 0.1
                && Math.abs(temperatureC - o.temperatureC) < 2 * tolerance * 100
                && Math.abs(silicaWt - o.silicaWt) < tolerance * 10
                && Math.abs(dissolvedWaterWt - o.dissolvedWaterWt) < tolerance
                && Math.abs(co2Wt - o.co2Wt) < tolerance * 0.1
                && Math.abs(exsolvedGasMassFraction - o.exsolvedGasMassFraction) < tolerance * 1e-2
                && Math.abs(crystalFraction - o.crystalFraction) < tolerance * 0.1
                && radiusM == o.radiusM && lengthM == o.lengthM
                && rel(ventAmbientPressurePa, o.ventAmbientPressurePa) < tolerance
                && (Double.isNaN(lithostaticPressurePa) ? Double.isNaN(o.lithostaticPressurePa)
                        : rel(lithostaticPressurePa, o.lithostaticPressurePa) < tolerance * 0.1)
                && java.util.Objects.equals(shape, o.shape);
    }

    /**
     * The same input with every driver rounded to a resolution finer than the model can tell apart:
     * forecasts keyed on it are reproducible and need re-solving only when the state really moved.
     */
    public ConduitInput rounded() {
        return new ConduitInput(round(chamberPressurePa, 2.5e5), round(temperatureC, 2), round(silicaWt, 0.25),
                round(dissolvedWaterWt, 0.05), round(co2Wt, 0.02), round(exsolvedGasMassFraction, 1e-4),
                round(crystalFraction, 0.01), radiusM, lengthM, Math.max(1, round(ventAmbientPressurePa, 1e3)),
                round(waterTableDepthM, 10), round(lithostaticPressurePa, 2.5e5), shape);
    }

    private static double round(double v, double step) {
        return Math.rint(v / step) * step;
    }

    private static double rel(double a, double b) {
        return Math.abs(a - b) / Math.max(Math.abs(a), Math.abs(b));
    }
}
