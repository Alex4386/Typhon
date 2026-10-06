package me.alex4386.typhon.engine.geomorph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.geomorph.GeomorphEvents.CraterExcavated;
import me.alex4386.typhon.engine.geomorph.GeomorphEvents.FailureStyle;
import me.alex4386.typhon.engine.geomorph.GeomorphEvents.SlopeFailure;
import me.alex4386.typhon.engine.geomorph.GeomorphEvents.Trigger;
import me.alex4386.typhon.engine.massflow.DebrisAvalanches;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitTable;
import org.junit.jupiter.api.Test;

class GeomorphologyTest {
    private static final int BASE_Y = 64;
    private static final double BASE_Z = BASE_Y + 1; // surface elevation of the flat ground (m)

    /** Saturated ground: water table at the surface, everything else dry and cold. */
    private static final GroundState SATURATED = new StubGround(0, 0, 15);

    record StubGround(double waterTableDepth, double vadose, double temperature) implements GroundState {
        @Override
        public double temperatureC(int x, int z, double depthM) {
            return temperature;
        }

        @Override
        public double waterTableDepthM(int x, int z) {
            return waterTableDepth;
        }

        @Override
        public double vadoseM(int x, int z) {
            return vadose;
        }

        @Override
        public double surfaceWaterDepthM(int x, int z) {
            return 0;
        }

        @Override
        public double steamFraction(int x, int z, double depthM) {
            return 0;
        }
    }

    private static GeomorphConfig config() {
        GeomorphConfig c = new GeomorphConfig();
        c.sweepTilesPerStep = 0; // tests activate what they build
        c.reportMinVolumeM3 = 0; // report every failure as a SlopeFailure
        return c;
    }

    /** A cone of loose scoria {@code height} m high with flanks at {@code slopeDeg}, centred in the world. */
    private static void scoriaCone(GeoWorld w, int cx, int cz, double height, double slopeDeg) {
        double t = Math.tan(Math.toRadians(slopeDeg));
        for (int x = 0; x < w.size; x++) {
            for (int z = 0; z < w.size; z++) {
                double r = Math.hypot(x - cx, z - cz);
                double h = height - r * t;
                if (h > 0) w.world.deposit(x, z, h, MaterialTable.SCORIA, UnitTable.UNATTRIBUTED);
            }
        }
    }

    /** A scoria plateau ending in a planar ramp descending towards +x at {@code slopeDeg}, {@code length} m long. */
    private static void scoriaRamp(GeoWorld w, int x0, int length, double slopeDeg) {
        double t = Math.tan(Math.toRadians(slopeDeg));
        for (int x = 0; x < x0 + length; x++) {
            for (int z = 0; z < w.size; z++) {
                double h = Math.min(length, x0 + length - x) * t;
                w.world.deposit(x, z, h, MaterialTable.SCORIA, UnitTable.UNATTRIBUTED);
            }
        }
    }

    private static Geomorphology geo(GeoWorld w, GroundState ground) {
        Geomorphology g = new Geomorphology("geomorph:test", "test", w.terrain, config());
        g.setGround(ground);
        return g;
    }

    @Test
    void dryScoriaConeRelaxesToItsAngleOfRepose() {
        GeoWorld w = GeoWorld.flat(5, BASE_Y);
        scoriaCone(w, 40, 40, 20, 50);
        double before = w.solid(MaterialTable.SCORIA, BASE_Z - 1e-9);
        Geomorphology g = geo(w, GroundState.DRY);
        g.activateArea(0, 0, w.size - 1, w.size - 1);
        Engine engine = w.engine(1, 1, g);
        w.settle(engine, g, 400);

        assertEquals(0, g.queuedColumns(), "the cone should come to rest");
        double slope = w.maxSlopeDeg(5, 5, w.size - 6, w.size - 6);
        assertTrue(slope <= 34.5, "no slope steeper than the scoria repose angle: " + slope);
        assertTrue(slope >= 30, "flanks stay near repose, not flattened: " + slope);
        double after = w.solid(MaterialTable.SCORIA, BASE_Z - 1e-9);
        // layer tops are stored as 32-bit floats: allow their rounding
        assertEquals(before, after, 1e-5 * before, "solid volume is conserved");
        assertTrue(g.failureCount() > 0);
    }

    @Test
    void saturatedSlopeFailsAtAGentlerAngle() {
        GeoWorld w = GeoWorld.flat(5, BASE_Y);
        scoriaCone(w, 40, 40, 20, 50);
        Geomorphology g = geo(w, SATURATED);
        g.activateArea(0, 0, w.size - 1, w.size - 1);
        Engine engine = w.engine(1, 1, g);
        w.settle(engine, g, 800);
        double slope = w.maxSlopeDeg(5, 5, w.size - 6, w.size - 6);
        // slope-parallel seepage: tanβ_c = (1 − γ_w/γ_sat) tanφ ≈ 0.5·tan 34° → ≈ 19°
        assertTrue(slope < 24, "saturated scoria stands far below its dry repose angle: " + slope);
    }

    @Test
    void stableDrySlopeFailsWhenRainSoaksIt() {
        GeoWorld dry = GeoWorld.flat(4, BASE_Y);
        scoriaRamp(dry, 10, 30, 30);
        Geomorphology g = geo(dry, GroundState.DRY);
        g.activateArea(0, 0, dry.size - 1, dry.size - 1);
        dry.settle(dry.engine(1, 1, g), g, 50);
        assertEquals(0, g.failureCount(), "a 30° scoria slope is stable dry (FS = tan34/tan30 = 1.17)");

        GeoWorld wet = GeoWorld.flat(4, BASE_Y);
        scoriaRamp(wet, 10, 30, 30);
        // 6 m of infiltrated rain in pore space 0.5 → wetting front 12 m deep, below the deposit
        Geomorphology h = geo(wet, new StubGround(Double.POSITIVE_INFINITY, 6, 15));
        h.activateArea(0, 0, wet.size - 1, wet.size - 1);
        wet.run(wet.engine(1, 1, h), 5);
        assertTrue(h.failureCount() > 0, "the soaked slope fails");
        List<SlopeFailure> failures = wet.events(SlopeFailure.class);
        assertFalse(failures.isEmpty());
        assertEquals(Trigger.PORE_PRESSURE, failures.get(0).trigger());
    }

    @Test
    void earthquakeTriggersAMarginalSlope() {
        GeoWorld calm = GeoWorld.flat(4, BASE_Y);
        scoriaRamp(calm, 10, 30, 32);
        Geomorphology g = geo(calm, GroundState.DRY);
        g.activateArea(0, 0, calm.size - 1, calm.size - 1);
        calm.settle(calm.engine(1, 1, g), g, 50);
        assertEquals(0, g.failureCount(), "32° is below the 34° repose angle");

        GeoWorld shaken = GeoWorld.flat(4, BASE_Y);
        scoriaRamp(shaken, 10, 30, 32);
        Geomorphology h = geo(shaken, GroundState.DRY);
        Engine engine = shaken.engine(1, 1, h);
        h.queueQuake(new BlockPos(25, 0, 32), 4.0); // PGA ≈ 0.13 g → k_h ≈ 0.065 > 0.035 needed
        shaken.settle(engine, h, 300);
        assertTrue(h.failureCount() > 0, "shaking tips the marginal slope");
        assertEquals(Trigger.SEISMIC, shaken.events(SlopeFailure.class).get(0).trigger());
    }

    /** A 70° andesite cliff 40 m high descending toward +x. */
    private static GeoWorld cliff() {
        return cliff(6);
    }

    private static GeoWorld cliff(int chunks) {
        return new GeoWorld(chunks, (x, z) -> x < 30 ? BASE_Y + 40 : Math.max(BASE_Y, BASE_Y + 40 - (int) Math.round((x - 30) * Math.tan(Math.toRadians(70)))));
    }

    @Test
    void freshLavaCliffStandsButAlteredOneCollapses() {
        GeoWorld fresh = cliff();
        Geomorphology g = geo(fresh, GroundState.DRY);
        g.activateArea(0, 0, fresh.size - 1, fresh.size - 1);
        fresh.settle(fresh.engine(1, 1, g), g, 50);
        assertEquals(0, g.failureCount(), "a 40 m fresh lava cliff stands (Culmann H_c ≈ 500 m)");

        GeoWorld altered = cliff();
        Geomorphology h = geo(altered, GroundState.DRY);
        for (int x = 0; x < altered.size; x++) for (int z = 0; z < altered.size; z++) h.setAlteration(x, z, 1);
        altered.run(altered.engine(1, 1, h), 10);
        assertTrue(h.failureCount() > 0, "completely altered rock (c ≈ 40 kPa, φ ≈ 22°) cannot hold it");
        assertEquals(Trigger.ALTERATION, altered.events(SlopeFailure.class).get(0).trigger());
    }

    @Test
    void largeFailureRunsOutAsADebrisAvalancheConservingMass() {
        GeoWorld w = cliff();
        double before = w.solid(null, 0);
        GeomorphConfig c = config();
        c.avalancheMinVolumeM3 = 500;
        Geomorphology g = new Geomorphology("geomorph:test", "test", w.terrain, c);
        DebrisAvalanches flow = new DebrisAvalanches("avalanche:test", w.terrain);
        g.setFlows(flow, null, null);
        for (int x = 0; x < w.size; x++) for (int z = 0; z < w.size; z++) g.setAlteration(x, z, 1);
        Engine engine = w.engine(3, 1, g, flow);
        w.run(engine, 5);
        int n = 0;
        while (flow.activeCellCount() > 0 && n++ < 300) w.run(engine, 1);
        assertTrue(w.events(SlopeFailure.class).stream().anyMatch(f -> f.style() == FailureStyle.DEBRIS_AVALANCHE));
        assertTrue(w.solid(MaterialTable.DEBRIS, 0) > 0, "the avalanche left a debris deposit");
        double after = w.solid(null, 0);
        double lost = flow.massBudget().lost() * (1 - MaterialTable.DEBRIS.porosity());
        assertEquals(before, after + lost, 1e-7 * before, "solids conserved (flow losses at the edge accounted)");
    }

    @Test
    void craterSizeScalesWithExplosionEnergy() {
        double[] energies = {1e10, 1e12};
        double[] radii = new double[2];
        for (int i = 0; i < 2; i++) {
            GeoWorld w = GeoWorld.flat(20, BASE_Y);
            Geomorphology g = geo(w, GroundState.DRY);
            Engine engine = w.engine(1, 1, g);
            double before = w.solid(null, 0);
            g.queueExplosion(new BlockPos(160, BASE_Y, 160), energies[i]);
            w.run(engine, 1);
            CraterExcavated e = w.events(CraterExcavated.class).get(0);
            assertEquals(0.5 * CraterScaling.diameter(energies[i]), e.radiusM(), 1e-9);
            assertTrue(e.excavatedM3() > 0);
            // ejecta conserve the excavated solids
            assertEquals(before, w.solid(null, 0), 1e-7 * before);
            Geomorphology.CraterShape shape = g.crater(new BlockPos(160, BASE_Y, 160), 200);
            assertEquals(e.radiusM(), shape.radiusM(), 0.25 * e.radiusM() + 1.5, "measured rim radius");
            // measured from the rim crest, which the ejecta raise above the old surface
            assertTrue(shape.depthM() >= e.depthM() - 1 && shape.depthM() <= 1.6 * e.depthM() + 1,
                    "measured depth " + shape + " vs " + e.depthM());
            radii[i] = shape.radiusM();
        }
        assertEquals(Math.pow(100, 1 / 3.05), radii[1] / radii[0], 0.2 * Math.pow(100, 1 / 3.05));
    }

    @Test
    void openVentWithFalloutBuildsARimmedCrater() {
        // proximal fallout ∝ exp(−r/λ), including into the vent itself
        for (boolean open : new boolean[] {false, true}) {
            GeoWorld w = GeoWorld.flat(5, BASE_Y);
            Geomorphology g = geo(w, GroundState.DRY);
            g.setVents(List.of(VentSite.crater("main", new BlockPos(40, BASE_Y, 40), 2)), () -> open);
            Engine engine = w.engine(1, 1, g);
            for (int burst = 0; burst < 60; burst++) {
                for (int x = 0; x < w.size; x++) {
                    for (int z = 0; z < w.size; z++) {
                        double r = Math.hypot(x - 40, z - 40);
                        double t = 0.6 * Math.exp(-r / 8);
                        if (t > 1e-3) w.world.deposit(x, z, t, MaterialTable.SCORIA, UnitTable.UNATTRIBUTED);
                    }
                }
                w.run(engine, 2);
            }
            w.settle(engine, g, 400);
            Geomorphology.CraterShape crater = g.crater(new BlockPos(40, BASE_Y, 40), 40);
            double slope = w.maxSlopeDeg(2, 2, w.size - 3, w.size - 3);
            assertTrue(slope <= 34.5, "walls and flanks at or below repose: " + slope);
            if (open) {
                assertTrue(crater.depthM() > 5, "an open vent swallows fallback: crater " + crater);
                assertTrue(crater.radiusM() > 3, "the rim stands away from the conduit: " + crater);
                assertTrue(g.recycledM3() > 0);
            } else {
                assertEquals(0, crater.depthM(), 1e-9, "a plugged vent is buried under a plain cone: " + crater);
            }
        }
    }

    // ── Determinism ──

    private static final int STEPS = 25;

    private static String scenario(int threads, InMemorySaveStore[] saveAt, int saveStep) {
        GeoWorld w = cliff(4);
        GeomorphConfig c = config();
        c.sweepTilesPerStep = 2;
        c.avalancheMinVolumeM3 = 500;
        Geomorphology g = new Geomorphology("geomorph:test", "test", w.terrain, c);
        DebrisAvalanches flow = new DebrisAvalanches("avalanche:test", w.terrain);
        g.setFlows(flow, null, null);
        g.setGround(new StubGround(5, 0.5, 120));
        for (int x = 0; x < 40; x++) for (int z = 0; z < 24; z++) g.setAlteration(x, z, 0.8);
        Engine engine = w.engine(9, threads, g, flow);
        g.queueExplosion(new BlockPos(20, BASE_Y + 40, 40), 1e11);
        g.queueQuake(new BlockPos(40, 0, 40), 3.5);
        for (int i = 0; i < STEPS; i++) {
            if (saveAt != null && i == saveStep) saveAt[0] = Saves.save(engine);
            w.run(engine, 1);
        }
        return engine.stateHash();
    }

    @Test
    void resultsDoNotDependOnThreadCount() {
        String one = scenario(1, null, -1);
        assertEquals(one, scenario(4, null, -1));
    }

    @Test
    void saveAndRestoreResumesBitForBit() {
        String straight = scenario(1, null, -1);
        InMemorySaveStore[] saved = new InMemorySaveStore[1];
        scenario(1, saved, 10);

        GeoWorld w = cliff(4);
        GeomorphConfig c = config();
        c.sweepTilesPerStep = 2;
        c.avalancheMinVolumeM3 = 500;
        Geomorphology g = new Geomorphology("geomorph:test", "test", w.terrain, c);
        DebrisAvalanches flow = new DebrisAvalanches("avalanche:test", w.terrain);
        g.setFlows(flow, null, null);
        g.setGround(new StubGround(5, 0.5, 120));
        Engine engine = Engine.builder(9).baseStepMicros(1_000_000).add(w.terrain).add(g).add(flow).restore(saved[0]).build();
        for (int i = 10; i < STEPS; i++) w.run(engine, 1);
        assertEquals(straight, engine.stateHash());
    }
}
