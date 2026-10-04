package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.ColumnProfile;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.simulator.run.Simulation;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.terrain.DemImporter;
import org.junit.jupiter.api.Test;

class RealPresetsTest {
    private static double elevation(ColumnGrid g, int x, int z, double L) {
        return (g.ground(x, z) + 1) * L;
    }

    @Test
    void syntheticEdificesMatchTheirPublishedDimensions() {
        for (Preset p : RealPresets.all()) {
            RealSetting s = p.realSetting();
            ColumnGrid g = p.terrain(1);
            double L = s.metersPerColumn();
            assertTrue(L >= 10 && L <= 30, p.name() + ": 10–30 m columns");
            assertTrue(s.domainMeters() >= 3500, p.name() + ": km-scale domain, got " + s.domainMeters() + " m");
            assertEquals(2 * ((s.halfExtentColumns() + 15) / 16) * 16, g.size());
            double summit = (g.maxGround() + 1) * L;
            ReferenceValue ref = p.referenceValues().stream()
                    .filter(r -> r.metric() == ReferenceValue.Metric.SUMMIT_ELEVATION_M).findFirst().orElse(null);
            if (ref != null) {
                assertEquals(ReferenceValue.Verdict.WITHIN, ref.judge(summit), p.name() + " summit " + summit + " m");
            }
        }
    }

    @Test
    void kilaueaHasACalderaAndAPitAndGentleFlanks() {
        Preset p = Presets.get("kilauea-real");
        ColumnGrid g = p.terrain(1);
        double L = 20;
        double pit = elevation(g, 0, 0, L);
        double caldera = elevation(g, 50, 0, L);     // 1 km out: caldera floor
        double rim = elevation(g, 100, 0, L);        // 2 km: just outside the rim
        double flank = elevation(g, 240, 0, L);      // 4.8 km
        assertTrue(pit < caldera - 50, "pit crater below the caldera floor: " + pit + " vs " + caldera);
        assertTrue(caldera < rim - 80, "caldera floor below the rim: " + caldera + " vs " + rim);
        double slope = Math.toDegrees(Math.atan((rim - flank) / ((240 - 100) * L)));
        assertTrue(slope > 2 && slope < 7, "shield flank slope 3-6°, got " + slope);
    }

    @Test
    void islandsAreFloodedAroundAndStromboliHasItsSciara() {
        Preset stromboli = Presets.get("stromboli-real");
        ColumnGrid g = stromboli.terrain(1);
        assertTrue(g.water(250, 0) != me.alex4386.typhon.engine.terrain.TerrainColumn.NO_WATER, "sea at 3.75 km");
        assertEquals(me.alex4386.typhon.engine.terrain.TerrainColumn.NO_WATER, g.water(0, 0), "summit dry");
        // Sciara del Fuoco: NW flank lower than the NE flank at the same distance
        int k = 50; // 750 m along each diagonal
        assertTrue(g.ground(-k, -k) < g.ground(k, -k) - 2, "NW trough below the NE flank");

        Preset surtsey = Presets.get("surtsey-real");
        ColumnGrid s = surtsey.terrain(1);
        assertTrue(elevation(s, 0, 0, 10) < 0, "seamount summit below sea level");
        assertEquals(-1, s.water(0, 0), "sea surface at 0 m is the top of block -1");
        assertTrue(elevation(s, 180, 0, 10) <= -120, "shelf at ~ -130 m");
    }

    @Test
    void importedStacksCarryTheGeologyAndTheEdifice() {
        Preset p = Presets.get("st-helens-real");
        Scenario scenario = p.build(3, p.terrain(3), Scenario.Options.DEFAULT);
        scenario.engine().step(); // applies the terrain snapshot
        WorldModel world = scenario.terrain().world();
        VentSite vent = scenario.volcano().vents().get(0);
        ColumnProfile summit = world.column(vent.position().x() + 3, vent.position().z());
        List<LayerView> layers = summit.layers();
        assertEquals(MaterialTable.GRANITE.id(), layers.get(0).material(), "basement cake at the bottom");
        assertEquals(-2000, layers.get(0).top(), 1e-3);
        assertEquals(MaterialTable.ANDESITE.id(), layers.get(1).material(), "country rock up to the edifice base");
        assertEquals(1100, layers.get(1).top(), 1e-3);
        assertEquals(MaterialTable.DACITE.id(), layers.get(2).material(), "dacite edifice");
        assertEquals("st-helens-real", world.unit(layers.get(2).unit()).volcanoId());
        assertEquals(DepositType.EDIFICE, world.unit(layers.get(2).unit()).type());

        ColumnProfile far = world.column(250, 250); // > 6 km away: outside the edifice
        assertTrue(far.layers().stream().noneMatch(l -> l.material() == MaterialTable.DACITE.id()));
    }

    @Test
    void ventsSitOnTheGroundAndChambersAtRealDepth() {
        for (Preset p : RealPresets.all()) {
            ColumnGrid g = p.terrain(2);
            Scenario sc = p.build(2, g, Scenario.Options.DEFAULT);
            double L = p.realSetting().metersPerColumn();
            for (VentSite v : sc.volcano().vents()) {
                assertEquals(g.ground(v.position().x(), v.position().z()), v.position().y(), p.name() + " vent " + v.id());
            }
            double chamberTop = (sc.volcano().chamber().chamberCenter().y() + 1) * L;
            double ventZ = (sc.volcano().vents().get(0).position().y() + 1) * L;
            assertTrue(ventZ - chamberTop >= 1000, p.name() + ": chamber ≥ 1 km below the vent");
        }
    }

    @Test
    void realPresetsRunDeterministicallyAndKilaueaErupts() {
        Simulation.Result a = new Simulation(Presets.get("kilauea-real").build(5), 10).run(60 / 3600.0);
        Simulation.Result b = new Simulation(Presets.get("kilauea-real").build(5), 10).run(60 / 3600.0);
        assertEquals(a.scenario().engine().stateHash(), b.scenario().engine().stateHash());
        assertEquals(1, a.summary().eruptions);
    }

    @Test
    void demSourcesNameTheirTiles() {
        RealSetting.DemSource kilauea = Presets.get("kilauea-real").realSetting().dem();
        assertEquals("N19W156", kilauea.srtmTile());
        assertEquals("Copernicus_DSM_COG_10_N19_00_W156_00_DEM.tif", kilauea.copernicusTile());
        assertEquals("https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N19_00_W156_00_DEM/"
                + "Copernicus_DSM_COG_10_N19_00_W156_00_DEM.tif", kilauea.copernicusUrl());
        assertEquals("N38E015", Presets.get("stromboli-real").realSetting().dem().srtmTile());
        assertEquals("S01W079", RealSetting.DemSource.srtmTileFor(-0.5, -78.2));
        assertEquals(-1, DemImporter.groundBlock(0, 20));
    }
}
