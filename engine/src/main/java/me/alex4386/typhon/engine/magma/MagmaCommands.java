package me.alex4386.typhon.engine.magma;

import me.alex4386.typhon.engine.command.EngineCommand;

/**
 * Commands accepted by {@link MagmaChamber}. Each is addressed to a chamber by its volcano id, so one
 * engine can host several volcanoes.
 */
public final class MagmaCommands {
    private MagmaCommands() {}

    public sealed interface MagmaCommand extends EngineCommand
            permits SetSupplyRate, SetSupplyMagma, InjectRecharge, StartEruption, StopEruption {
        String volcanoId();
    }

    /** Changes the steady deep supply rate (m³ per physical second). */
    public record SetSupplyRate(String volcanoId, double supplyRate) implements MagmaCommand {
        public SetSupplyRate {
            if (supplyRate < 0) throw new IllegalArgumentException("supplyRate must be >= 0");
        }
    }

    /**
     * Changes the deep supply: its rate and the magma it delivers. Every field is optional; {@code
     * null} keeps the current value. The supply mixes into the chamber continuously by mass and
     * enthalpy, so a hotter, wetter or more mafic supply changes the chamber (and what erupts)
     * gradually. Applied at the chamber's next step; persisted with the chamber's state.
     *
     * @param supplyRate DRE volume rate (m³ per physical second), &gt;= 0
     * @param temperatureC temperature of the supplied magma
     * @param silicaWt bulk SiO₂ (wt%)
     * @param waterWt H₂O dissolved in its melt (wt%)
     * @param co2Wt CO₂ in its melt (wt%)
     * @param crystalFraction crystal volume fraction it carries, in [0, 0.9]; crystals carry no
     *     latent heat, so a crystal-rich supply heats the chamber less
     * @param variability log-normal σ of per-step supply fluctuations, &gt;= 0
     */
    public record SetSupplyMagma(String volcanoId, Double supplyRate, Double temperatureC, Double silicaWt,
            Double waterWt, Double co2Wt, Double crystalFraction, Double variability) implements MagmaCommand {
        public SetSupplyMagma {
            if (supplyRate != null && !(supplyRate >= 0)) throw new IllegalArgumentException("supplyRate must be >= 0");
            validateMagma(temperatureC, silicaWt, waterWt, co2Wt, crystalFraction);
            if (variability != null && !(variability >= 0)) throw new IllegalArgumentException("variability must be >= 0");
        }
    }

    /**
     * Injects a batch of fresh magma at once (a recharge pulse): raises overpressure by its volume
     * and mixes its mass, volatiles and enthalpy into the chamber. Optional fields ({@code null})
     * take the value of the chamber's current deep supply.
     *
     * @param volume DRE volume (m³), &gt; 0
     * @param temperatureC temperature of the batch
     * @param silicaWt bulk SiO₂ (wt%)
     * @param waterWt H₂O dissolved in its melt (wt%)
     * @param co2Wt CO₂ in its melt (wt%); {@code null} = the supply's
     * @param crystalFraction crystal volume fraction; {@code null} = the supply's
     */
    public record InjectRecharge(String volcanoId, double volume, double temperatureC, double silicaWt, double waterWt,
            Double co2Wt, Double crystalFraction) implements MagmaCommand {
        public InjectRecharge {
            if (!(volume > 0)) throw new IllegalArgumentException("volume must be positive");
            validateMagma(temperatureC, silicaWt, waterWt, co2Wt, crystalFraction);
        }

        /** A batch with the supply's CO₂ and crystal content. */
        public InjectRecharge(String volcanoId, double volume, double temperatureC, double silicaWt, double waterWt) {
            this(volcanoId, volume, temperatureC, silicaWt, waterWt, null, null);
        }
    }

    /** Manual override: start an eruption now, raising overpressure to the roof strength if needed. */
    public record StartEruption(String volcanoId) implements MagmaCommand {}

    /**
     * Manual override: end the current eruption. The conduit seals and overpressure is relieved to the
     * end threshold so the chamber does not immediately re-erupt.
     */
    public record StopEruption(String volcanoId) implements MagmaCommand {}

    static void validateMagma(Double temperatureC, Double silicaWt, Double waterWt, Double co2Wt, Double crystalFraction) {
        if (temperatureC != null && !(temperatureC > 0 && temperatureC < 2000)) {
            throw new IllegalArgumentException("temperatureC must be in (0, 2000)");
        }
        if (silicaWt != null && !(silicaWt >= 35 && silicaWt <= 80)) {
            throw new IllegalArgumentException("silicaWt must be in [35, 80]");
        }
        if (waterWt != null && !(waterWt >= 0 && waterWt <= 15)) {
            throw new IllegalArgumentException("waterWt must be in [0, 15]");
        }
        if (co2Wt != null && !(co2Wt >= 0 && co2Wt <= 5)) throw new IllegalArgumentException("co2Wt must be in [0, 5]");
        if (crystalFraction != null && !(crystalFraction >= 0 && crystalFraction <= 0.9)) {
            throw new IllegalArgumentException("crystalFraction must be in [0, 0.9]");
        }
    }
}
