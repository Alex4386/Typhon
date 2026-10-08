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
import org.junit.jupiter.api.Test;

class RealPresetsTest {
    private static double elevation(ColumnGrid g, int x, int z) {
        return g.surfaceZ(x, z);
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
            double summit = g.maxSurfaceZ();
            ReferenceValue ref = p.referenceValues().stream()
                    .filter(r -> r.metric() == ReferenceValue.Metric.SUMMIT_ELEVATION_M).findFirst().orElse(null);
            if (ref != null) {
                assertEquals(ReferenceValue.Verdict.WITHIN, ref.judge(summit), p.name() + " summit " + summit + " m");
            }
        }
    }

    @Test
    void kilaueaHasACalderaAndAPitAndGentleFlanks() {
        Preset p = Presets.get("kilauea");
        ColumnGrid g = p.terrain(1);
        double L = 20;
        double pit = elevation(g, 0, 0);
        double caldera = elevation(g, 50, 0);     // 1 km out: caldera floor
        double rim = elevation(g, 100, 0);        // 2 km: just outside the rim
        double flank = elevation(g, 240, 0);      // 4.8 km
        assertTrue(pit < caldera - 50, "pit crater below the caldera floor: " + pit + " vs " + caldera);
        assertTrue(caldera < rim - 80, "caldera floor below the rim: " + caldera + " vs " + rim);
        double slope = Math.toDegrees(Math.atan((rim - flank) / ((240 - 100) * L)));
        assertTrue(slope > 2 && slope < 7, "shield flank slope 3-6°, got " + slope);
    }

    @Test
    void islandsAreFloodedAroundAndStromboliHasItsSciara() {
        Preset stromboli = Presets.get("stromboli");
        ColumnGrid g = stromboli.terrain(1);
        assertEquals(0, g.waterZ(250, 0), 1e-9, "sea at 3.75 km");
        assertTrue(Double.isNaN(g.waterZ(0, 0)), "summit dry");
        // Sciara del Fuoco: NW flank lower than the NE flank at the same distance
        int k = 50; // 750 m along each diagonal
        assertTrue(g.surfaceZ(-k, -k) < g.surfaceZ(k, -k) - 30, "NW trough below the NE flank");

        Preset surtsey = Presets.get("surtsey");
        ColumnGrid s = surtsey.terrain(1);
        assertTrue(elevation(s, 0, 0) < 0, "seamount summit below sea level");
        assertEquals(0, s.waterZ(0, 0), 1e-9, "sea surface at 0 m");
        assertTrue(elevation(s, 180, 0) <= -120, "shelf at ~ -130 m");
    }

    @Test
    void importedStacksCarryTheGeologyAndTheEdifice() {
        Preset p = Presets.get("st-helens");
        Scenario scenario = p.build(3, p.terrain(3), Scenario.Options.DEFAULT);
        scenario.engine().step(); // applies the terrain snapshot
        WorldModel world = scenario.terrain().world();
        VentSite vent = scenario.volcano().vents().get(0);
        double l = world.spec().metersPerColumn();
        ColumnProfile summit = world.column(vent.position().columnX(l) + 3, vent.position().columnZ(l));
        List<LayerView> layers = summit.layers();
        assertEquals(MaterialTable.GRANITE.id(), layers.get(0).material(), "basement cake at the bottom");
        assertEquals(-2000 + summit.uplift(), layers.get(0).top(), 1e-3, "elevations include the deformation");
        assertEquals(MaterialTable.ANDESITE.id(), layers.get(1).material(), "country rock up to the edifice base");
        assertEquals(1100 + summit.uplift(), layers.get(1).top(), 1e-3);
        assertEquals(MaterialTable.DACITE.id(), layers.get(2).material(), "dacite edifice");
        assertEquals("st-helens", world.unit(layers.get(2).unit()).volcanoId());
        assertEquals(DepositType.EDIFICE, world.unit(layers.get(2).unit()).type());

        ColumnProfile far = world.column(250, 250); // > 6 km away: outside the edifice
        assertTrue(far.layers().stream().noneMatch(layer -> layer.material() == MaterialTable.DACITE.id()));
    }

    @Test
    void ventsSitOnTheGroundAndChambersAtRealDepth() {
        for (Preset p : RealPresets.all()) {
            ColumnGrid g = p.terrain(2);
            Scenario sc = p.build(2, g, Scenario.Options.DEFAULT);
            double L = p.realSetting().metersPerColumn();
            for (VentSite v : sc.volcano().vents()) {
                assertEquals(g.surfaceZ(v.position().columnX(L), v.position().columnZ(L)), v.position().y(), 1e-9,
                        p.name() + " vent " + v.id());
            }
            double chamberTop = sc.volcano().chamber().chamberCenter().y();
            double ventZ = sc.volcano().vents().get(0).position().y();
            assertTrue(ventZ - chamberTop >= 1000, p.name() + ": chamber ≥ 1 km below the vent");
        }
    }

    @Test
    void realPresetsRunDeterministicallyAndKilaueaErupts() {
        Simulation.Result a = new Simulation(Presets.get("kilauea").build(5), 10).run(60 / 3600.0);
        Simulation.Result b = new Simulation(Presets.get("kilauea").build(5), 10).run(60 / 3600.0);
        assertEquals(a.scenario().engine().stateHash(), b.scenario().engine().stateHash());
        assertEquals(1, a.summary().eruptions);
    }

    @Test
    void demSourcesNameTheirTiles() {
        RealSetting.DemSource kilauea = Presets.get("kilauea").realSetting().dem();
        assertEquals("N19W156", kilauea.srtmTile());
        assertEquals("Copernicus_DSM_COG_10_N19_00_W156_00_DEM.tif", kilauea.copernicusTile());
        assertEquals("https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N19_00_W156_00_DEM/"
                + "Copernicus_DSM_COG_10_N19_00_W156_00_DEM.tif", kilauea.copernicusUrl());
        assertEquals("N38E015", Presets.get("stromboli").realSetting().dem().srtmTile());
        assertEquals("S01W079", RealSetting.DemSource.srtmTileFor(-0.5, -78.2));
    }
}
