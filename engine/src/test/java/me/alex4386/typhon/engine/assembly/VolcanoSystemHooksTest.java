package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.geothermal.GeothermalConfig;
import me.alex4386.typhon.engine.geothermal.GeothermalGrid;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import org.junit.jupiter.api.Test;

/** Wind, geothermal centring and prewarming hooks on {@link VolcanoSystem}. */
class VolcanoSystemHooksTest {
    private static final VentSite FLANK = VentSite.crater("flank", new Point3(600, 400, -200), 30);
    private static final VentSite SUMMIT = VentSite.crater("summit", new Point3(5, 600, 5), 40);

    private static VolcanoSystem.Builder builder() {
        TerrainModel terrain = new TerrainModel();
        return VolcanoSystem.builder("v", List.of(FLANK, SUMMIT), terrain, new LavaFlow(terrain))
                .chamber(MagmaChamberConfig.builder("v", new Point3(0, -3000, 0)).build());
    }

    @Test
    void windIsGivenInRealUnits() {
        VolcanoSystem volcano = builder().wind(10, 1.0, 0.2).build();
        assertEquals(10, volcano.tephra().wind().baseSpeed(), 1e-12, "real wind speed (m/s), unscaled");
        assertEquals(1.0, volcano.tephra().wind().baseDirectionRad());
        assertEquals(0.2, volcano.tephra().wind().variability());

        volcano.setWind(16, 2.0, 0);
        Engine engine = volcano.addTo(Engine.builder(1)).build();
        engine.step();
        assertEquals(16, volcano.tephra().wind().baseSpeed(), 1e-12);
        assertEquals(2.0, volcano.tephra().wind().baseDirectionRad());
    }

    @Test
    void geothermalGridIsCentredOnTheVolcanoNotTheFirstVent() {
        VolcanoSystem volcano = builder().build();
        GeothermalGrid grid = volcano.geothermal().grid();
        int extent = grid.sizeX() * grid.cellSize();
        assertEquals(-extent / 2, grid.minX(), "centred above the chamber (x = 0), not on the flank vent");
        assertEquals(-extent / 2, grid.minZ());

        VolcanoSystem custom = builder().geothermalCenter(new Point3(1005, 64, 1005)).build(); // column 100 at 10 m
        assertEquals(100 - extent / 2, custom.geothermal().grid().minX());
    }

    @Test
    void prewarmHookSetsTheSpinUp() {
        GeothermalConfig config = new GeothermalConfig();
        VolcanoSystem volcano = builder().geothermal(config).geothermalPrewarm(3600).build();
        assertTrue(volcano.geothermal() != null);
        assertEquals(3600, config.prewarmSeconds);
    }
}
