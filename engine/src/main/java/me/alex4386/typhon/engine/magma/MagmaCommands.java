package me.alex4386.typhon.engine.magma;

import me.alex4386.typhon.engine.command.EngineCommand;

/**
 * Commands accepted by {@link MagmaChamber}. Each is addressed to a chamber by its volcano id, so one
 * engine can host several volcanoes.
 */
public final class MagmaCommands {
    private MagmaCommands() {}

    public sealed interface MagmaCommand extends EngineCommand
            permits SetSupplyRate, InjectRecharge, StartEruption, StopEruption {
        String volcanoId();
    }

    /** Changes the steady deep supply rate (m³ per physical second). */
    public record SetSupplyRate(String volcanoId, double supplyRate) implements MagmaCommand {
        public SetSupplyRate {
            if (supplyRate < 0) throw new IllegalArgumentException("supplyRate must be >= 0");
        }
    }

    /**
     * Injects a batch of fresh magma at once (a recharge pulse): raises overpressure and mixes the
     * batch's heat, silica and water into the chamber.
     */
    public record InjectRecharge(String volcanoId, double volume, double temperatureC, double silicaWt, double waterWt)
            implements MagmaCommand {
        public InjectRecharge {
            if (volume <= 0) throw new IllegalArgumentException("volume must be positive");
        }
    }

    /** Manual override: start an eruption now, raising overpressure to the roof strength if needed. */
    public record StartEruption(String volcanoId) implements MagmaCommand {}

    /**
     * Manual override: end the current eruption. The conduit seals and overpressure is relieved to the
     * end threshold so the chamber does not immediately re-erupt.
     */
    public record StopEruption(String volcanoId) implements MagmaCommand {}
}
