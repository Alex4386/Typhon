package me.alex4386.typhon.engine.magma.conduit;

/**
 * A steady conduit flow from the chamber to the vent (see {@code docs/eruption-dynamics.md}).
 *
 * <p>All quantities are physical (real time, SI units unless stated).
 *
 * @param massFluxKgPerS total mass flux (magma + carried gas)
 * @param dreRateM3PerS dense-rock-equivalent volume flux of magma
 * @param exitVelocity mixture velocity at the vent (m/s)
 * @param exitPressurePa mixture pressure at the vent; above ambient when the flow is choked
 * @param choked whether the flow reaches the mixture sound speed at the vent
 * @param exitGasMassFraction gas still carried by the mixture at the vent
 * @param exitGasVolumeFraction gas volume fraction at the vent
 * @param exitGasConstant specific gas constant of the carried gas (J/kg/K): 461.5 for pure H₂O,
 *     lower as CO₂ (188.9) joins it
 * @param fragmentation how (and whether) the magma fragmented in the conduit
 * @param fragmentationDepthM depth below the vent of the fragmentation level ({@code NaN} if none)
 * @param exitMeltViscosityLog10 log10 of the melt + crystal viscosity at the vent, at the vent's
 *     dissolved water and crystallinity (Pa·s); what the erupted lava inherits
 * @param exitCrystalFraction total crystal fraction at the vent (chamber crystals + microlites)
 * @param exitTemperatureC temperature at the vent
 * @param exitDissolvedWaterWt H₂O still dissolved in the melt at the vent (wt%)
 * @param outgassedFraction share of the exsolved gas that left the mixture permeably during ascent
 * @param segregatedGasFraction share of the exsolved gas that segregated as coalescing slugs
 * @param ascentVelocity melt ascent speed in the lower conduit (m/s)
 * @param totalGasMassFraction all magmatic gas exsolved by the time the magma reaches the vent
 */
public record ConduitSolution(
        double massFluxKgPerS,
        double dreRateM3PerS,
        double exitVelocity,
        double exitPressurePa,
        boolean choked,
        double exitGasMassFraction,
        double exitGasVolumeFraction,
        double exitGasConstant,
        Fragmentation fragmentation,
        double fragmentationDepthM,
        double exitMeltViscosityLog10,
        double exitCrystalFraction,
        double exitTemperatureC,
        double exitDissolvedWaterWt,
        double outgassedFraction,
        double segregatedGasFraction,
        double ascentVelocity,
        double totalGasMassFraction) {

    /** How the magma broke up, if it did. */
    public enum Fragmentation {
        /** Coherent magma reached the vent. */
        NONE,
        /** Viscous melt failed brittlely under the expansion strain rate (Papale 1999): fine ash. */
        BRITTLE,
        /** A rapidly expanding foam of fluid melt tore apart (Namiki &amp; Manga 2008): coarse clots. */
        INERTIAL,
        /** A foam of intermediate viscosity reached the critical gas volume fraction. */
        FOAM
    }

    public boolean fragmented() {
        return fragmentation != Fragmentation.NONE;
    }

    /** Flux of gas that escaped the mixture permeably and vents passively at the surface (kg/s). */
    public double passiveGasFluxKgPerS() {
        return massFluxKgPerS * totalGasMassFraction * outgassedFraction;
    }

    /** Flux of gas segregated into slugs (kg/s). */
    public double slugGasFluxKgPerS() {
        return massFluxKgPerS * totalGasMassFraction * segregatedGasFraction;
    }

    /** Magma (melt + crystals, gas excluded) mass flux (kg/s). */
    public double magmaMassFluxKgPerS() {
        return massFluxKgPerS * (1 - exitGasMassFraction);
    }

    /** Descriptive two-phase flow pattern at the vent (Gonnermann &amp; Manga 2007); not used for behaviour. */
    public String flowPattern() {
        if (fragmented()) return "FRAGMENTED";
        if (segregatedGasFraction > 0.5) return "SLUG";
        if (exitGasVolumeFraction > 0.6) return "CHURN";
        if (exitGasVolumeFraction > 0.05) return "BUBBLY";
        return "DEGASSED";
    }
}
