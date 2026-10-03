package me.alex4386.typhon.engine.alert;

import me.alex4386.typhon.engine.magma.MeltViscosity;
import me.alex4386.typhon.engine.volcano.MagmaState;

/**
 * Suggests an eruption style from the magma's bulk viscosity, dissolved water and eruption rate.
 *
 * <p>Viscosity decides whether gas escapes freely (fluid basalt: Hawaiian/Strombolian) or is trapped
 * (viscous magma: Vulcanian and beyond); water content decides how much expansion drives
 * fragmentation; eruption rate separates sustained Plinian columns from discrete explosions or dome
 * collapse. Degassed viscous magma extrudes as a dome.
 */
public final class EruptionStyleClassifier {
    private EruptionStyleClassifier() {}

    public static EruptionStyle classify(MagmaState magma) {
        double viscosity = MeltViscosity.log10(magma.silicaWt(), magma.waterWt(), magma.temperatureC(), magma.crystalFraction());
        return classify(viscosity, magma.waterWt(), magma.eruptionRate());
    }

    /**
     * @param viscosityLog10 log10 bulk viscosity (Pa·s)
     * @param waterWt dissolved H₂O (wt%)
     * @param eruptionRate DRE eruption rate (m³/s)
     */
    public static EruptionStyle classify(double viscosityLog10, double waterWt, double eruptionRate) {
        if (viscosityLog10 < 2.5) {
            return waterWt >= 2.5 ? EruptionStyle.STROMBOLIAN : EruptionStyle.HAWAIIAN;
        }
        if (viscosityLog10 < 4) {
            return waterWt >= 3 ? EruptionStyle.VULCANIAN : EruptionStyle.STROMBOLIAN;
        }
        if (waterWt < 2) {
            return EruptionStyle.LAVA_DOME;
        }
        if (viscosityLog10 < 7) {
            return waterWt >= 4 && eruptionRate >= 20 ? EruptionStyle.PLINIAN : EruptionStyle.VULCANIAN;
        }
        if (waterWt >= 3.5 && eruptionRate >= 10) {
            return EruptionStyle.PLINIAN;
        }
        return EruptionStyle.PELEAN;
    }
}
