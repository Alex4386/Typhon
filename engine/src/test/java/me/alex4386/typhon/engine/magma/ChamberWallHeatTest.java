package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;

/** The heat a chamber reports giving to its wall rock is exactly what its cooling removes. */
class ChamberWallHeatTest {
    @Test
    void wallPowerMatchesTheEnergyTheChamberLoses() {
        MagmaChamberConfig config = MagmaChamberConfig.builder("v", Point3.ofBlock(new BlockPos(0, -40, 0), 1))
                .volume(1e9)
                .supplyRate(0)
                .supplyVariability(0)
                .coolingTimescale(1e10)
                .build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).adaptive(3600).add(chamber).build();
        double t0 = chamber.temperatureC();
        double power = chamber.wallHeatPowerW();
        engine.step(); // one long quiet step (the chamber covers up to its next 1 s schedule point)
        double t1 = chamber.temperatureC();
        double period = config.stepPeriodSeconds();
        double physicalSeconds = Math.ceil(engine.lastStepSeconds() / period - 1e-9) * period;

        double liquidus = chamber.liquidusC();
        double cEff = MagmaChamber.MELT_HEAT_CAPACITY
                + (t0 > MagmaChamber.SOLIDUS_C && t0 < liquidus
                        ? MagmaChamber.LATENT_HEAT_CRYSTALLISATION / (liquidus - MagmaChamber.SOLIDUS_C) : 0);
        double lost = MagmaChamber.MAGMA_DENSITY * cEff * config.volume() * (t0 - t1);
        assertTrue(power > 1e7, "a 1 km³ chamber loses tens of MW or more: " + power);
        assertEquals(lost, power * physicalSeconds, 1e-3 * lost);
    }
}
