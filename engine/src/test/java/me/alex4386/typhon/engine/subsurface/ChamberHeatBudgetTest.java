package me.alex4386.typhon.engine.subsurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

/** A chamber feeds the hydrothermal system at most the heat it loses through its wall. */
class ChamberHeatBudgetTest {
    private static final double DAY = 86400;

    record FixedSources(List<Chamber> chambers, List<Vent> vents) implements HeatSources {}

    /** A shallow chamber under a wet basalt plateau: the regime where boiling ran away. */
    private static Subsurface run(double wallPowerW, int days) {
        SubsurfaceConfig c = SubsurfaceTestWorld.config();
        c.initialWaterTableDepthM = 20;
        WorldModel world = SubsurfaceTestWorld.uniform("basalt", 50, 100, Double.NaN, 24, 24, (x, z) -> 0);
        Subsurface s = new Subsurface(world, c);
        s.prepare();
        HeatSources.Chamber chamber = new HeatSources.Chamber(12, 12, -700, 0, 350, 1150, wallPowerW);
        s.setHeatSources("v", new FixedSources(List.of(chamber), List.of()));
        for (int d = 0; d < days; d++) s.macroStep(DAY, DAY, false);
        return s;
    }

    @Test
    void deliveredHeatNeverExceedsTheWallPower() {
        double power = 2e7;
        int days = 200;
        Subsurface s = run(power, days);
        double available = power * days * DAY; // the chamber lies wholly inside the 2.4 km grid
        assertTrue(s.chamberHeatJ() > 0.5 * available, "the cold margin absorbs most of the supply: " + s.chamberHeatJ());
        assertTrue(s.chamberHeatJ() <= available * (1 + 1e-9), "never more than the budget: " + s.chamberHeatJ());
        // Boiling a cubic metre of water from ~20 °C takes ≥ ρ(cΔT + L) ≈ 2.6 GJ.
        double maxBoiled = s.chamberHeatJ() / (1000 * (4186 * 80 + 2.26e6));
        assertTrue(s.boiledM3() <= maxBoiled * 1.01, "boiled " + s.boiledM3() + " m³ > energy allows " + maxBoiled);
    }

    /**
     * Magma holds no groundwater: a chamber's own cells must not boil. (Re-heated every step to the
     * chamber temperature, saturated magma cells used to flash their pore water every step without
     * end — tens of km³ in a few hours at Kīlauea.)
     */
    @Test
    void magmaCellsDoNotBoil() {
        Subsurface fixed = run(Double.NaN, 30);
        assertEquals(0, fixed.boiledM3(), 1e-6, "only conduction out of the chamber can boil water, and it is slow");
    }

    @Test
    void shareOfTheWallAboveTheGridBottom() {
        HeatSources.Chamber whollyAbove = new HeatSources.Chamber(0, 0, -1000, 0, 500, 1100, 1);
        HeatSources.Chamber half = new HeatSources.Chamber(0, 0, -2400, 0, 500, 1100, 1);
        HeatSources.Chamber below = new HeatSources.Chamber(0, 0, -4000, 0, 500, 1100, 1);
        assertEquals(1, SubsurfaceHeat.shareAboveGridBottom(whollyAbove, 2400), 1e-12);
        assertEquals(0.5, SubsurfaceHeat.shareAboveGridBottom(half, 2400), 1e-12);
        assertEquals(0, SubsurfaceHeat.shareAboveGridBottom(below, 2400), 1e-12);
    }
}
