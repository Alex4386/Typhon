package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.geothermal.GeothermalConfig;
import me.alex4386.typhon.engine.geothermal.GeothermalGrid;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import org.junit.jupiter.api.Test;

/** Wind, geothermal centring and prewarming hooks on {@link VolcanoSystem}. */
class VolcanoSystemHooksTest {
    private static final VentSite FLANK = VentSite.crater("flank", Point3.surfaceOf(new BlockPos(60, 90, -20), VolcanoScaling.DEFAULT.metersPerBlock()), 3);
    private static final VentSite SUMMIT = VentSite.crater("summit", Point3.surfaceOf(new BlockPos(0, 120, 0), VolcanoScaling.DEFAULT.metersPerBlock()), 4);

    private static VolcanoSystem.Builder builder() {
        TerrainModel terrain = new TerrainModel();
        return VolcanoSystem.builder("v", List.of(FLANK, SUMMIT), terrain, new LavaFlow(terrain))
                .chamber(MagmaChamberConfig.builder("v", Point3.ofBlock(new BlockPos(0, 40, 0), VolcanoScaling.DEFAULT.metersPerBlock())).build())
                .scaling(new VolcanoScaling(4, 100));
    }

    @Test
    void windIsGivenInRealUnitsAndScaled() {
        VolcanoSystem volcano = builder().wind(10, 1.0, 0.2).build();
        assertEquals(10 / Math.sqrt(4), volcano.tephra().wind().baseSpeed(), 1e-12, "Froude: v / sqrt(L)");
        assertEquals(1.0, volcano.tephra().wind().baseDirectionRad());
        assertEquals(0.2, volcano.tephra().wind().variability());

        volcano.setWind(16, 2.0, 0);
        Engine engine = volcano.addTo(Engine.builder(1)).build();
        engine.step();
        assertEquals(16 / Math.sqrt(4), volcano.tephra().wind().baseSpeed(), 1e-12);
        assertEquals(2.0, volcano.tephra().wind().baseDirectionRad());
    }

    @Test
    void geothermalGridIsCentredOnTheVolcanoNotTheFirstVent() {
        VolcanoSystem volcano = builder().build();
        GeothermalGrid grid = volcano.geothermal().grid();
        int extent = grid.sizeX() * grid.cellSize();
        assertEquals(-extent / 2, grid.minX(), "centred above the chamber (x = 0), not on the flank vent");
        assertEquals(-extent / 2, grid.minZ());

        VolcanoSystem custom = builder().geothermalCenter(Point3.surfaceOf(new BlockPos(100, 64, 100), VolcanoScaling.DEFAULT.metersPerBlock())).build();
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
