package me.alex4386.typhon.engine.world;

/**
 * Physical properties of one material, in SI units.
 *
 * @param id index in {@link MaterialTable}
 * @param name stable lowercase name used in configuration files
 * @param densityKgM3 bulk density (including typical pore space)
 * @param conductivityWmK thermal conductivity
 * @param heatCapacityJkgK specific heat capacity
 * @param log10HydraulicConductivity log₁₀ of hydraulic conductivity K (m/s); {@code NaN} if not porous
 * @param porosity typical total porosity, 0–1
 * @param solidusC solidus temperature (°C) for igneous rock, {@code NaN} otherwise
 * @param liquidusC liquidus temperature (°C) for igneous rock, {@code NaN} otherwise
 * @param erodibility relative ease of entrainment by flows, 0 (hard rock) – 1 (loose ash)
 * @param loose whether fresh layers of this material are unconsolidated (lahar-erodible) by default
 */
public record Material(
        short id,
        String name,
        MaterialClass materialClass,
        double densityKgM3,
        double conductivityWmK,
        double heatCapacityJkgK,
        double log10HydraulicConductivity,
        double porosity,
        double solidusC,
        double liquidusC,
        double erodibility,
        boolean loose) {

    public boolean solid() {
        return materialClass.solid();
    }

    /** Thermal diffusivity κ = k / (ρ c), m²/s. */
    public double diffusivity() {
        return conductivityWmK / (densityKgM3 * heatCapacityJkgK);
    }
}
