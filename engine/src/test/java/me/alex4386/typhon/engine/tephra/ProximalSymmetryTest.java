package me.alex4386.typhon.engine.tephra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLanded;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.volcano.VentSite;
import org.junit.jupiter.api.Test;

/**
 * Proximal tephra of discrete explosions must pile up symmetrically around the vent in still air on
 * flat ground (no azimuthal, launch-angle or grid-rounding bias), and drift only modestly with wind
 * while fine ash is carried downwind. Ground of 1 m columns at 100 m; 100 m ash cells.
 */
class ProximalSymmetryTest {
    private static final double GROUND = 100;
    private static final VentSite VENT = VentSite.crater("summit", new Point3(3.5, GROUND, -4.5), 2);
    private static final double CX = VENT.position().x();
    private static final double CZ = VENT.position().z();

    private static TephraConfig config() {
        TephraConfig c = new TephraConfig();
        c.gridCells = 64;
        return c;
    }

    private record Setup(Engine engine, TephraSubsystem tephra, TerrainModel terrain) {}

    private static Setup setup(double windSpeed) {
        TerrainModel terrain = TestGround.terrain(1.0);
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, config());
        Engine engine = Engine.builder(17).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(12, GROUND));
        tephra.setWind(windSpeed, 0, 0); // towards +X
        engine.step();
        return new Setup(engine, tephra, terrain);
    }

    private static List<EngineFrame> run(Engine engine, int steps) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < steps; i++) frames.add(engine.step());
        return frames;
    }

    /** Mass-weighted direction of {@code (thickness, x, z)} samples around the vent: |resultant|, its x component, mean radius. */
    private static double[] resultant(List<double[]> samples) {
        double sx = 0, sz = 0, total = 0, meanR = 0;
        for (double[] sample : samples) {
            double h = sample[0];
            if (h <= 0) continue;
            double x = sample[1] - CX, z = sample[2] - CZ;
            double r = Math.hypot(x, z);
            if (r < 1e-9) continue;
            sx += h * x / r;
            sz += h * z / r;
            meanR += h * r;
            total += h;
        }
        assertTrue(total > 0, "a deposit formed");
        return new double[] {Math.hypot(sx, sz) / total, sx / total, meanR / total};
    }

    /** The proximal pile on the ground: column thickness within {@code radius} m of the vent. */
    private static double[] groundResultant(Setup s, int radius) {
        List<double[]> samples = new ArrayList<>();
        int vx = (int) Math.floor(CX), vz = (int) Math.floor(CZ);
        for (int z = vz - radius; z <= vz + radius; z++) {
            for (int x = vx - radius; x <= vx + radius; x++) {
                samples.add(new double[] {s.terrain().world().surfaceZ(x, z) - GROUND, x + 0.5, z + 0.5});
            }
        }
        return resultant(samples);
    }

    /** The fall recorded by the ash grid: cell thickness within {@code radius} m of the vent. */
    private static double[] gridResultant(TephraSubsystem t, double radius) {
        AshGrid grid = t.grid();
        assertTrue(grid != null, "an ash grid formed");
        List<double[]> samples = new ArrayList<>();
        for (int j = 0; j < grid.cells; j++) {
            for (int i = 0; i < grid.cells; i++) {
                Point3 c = grid.cellCenter(i, j);
                if (Math.hypot(c.x() - CX, c.z() - CZ) > radius) continue;
                samples.add(new double[] {grid.thickness(grid.index(i, j), t.config().depositBulkDensity), c.x(), c.z()});
            }
        }
        return resultant(samples);
    }

    @Test
    void stillAirBombSalvosLandSymmetrically() {
        Setup s = setup(0);
        for (int i = 0; i < 40; i++) s.tephra().launchSalvo(VENT, 4e4, 120, 0, 20, 50, 400, true);
        List<EngineFrame> frames = run(s.engine(), 20 * 60);
        List<BombLanded> landed = TephraTestSupport.events(frames, BombLanded.class);
        assertTrue(landed.size() > 1000, "enough bombs: " + landed.size());
        double sx = 0, sz = 0;
        int n = 0;
        int vertical = 0;
        for (BombLanded b : landed) {
            double x = b.position().x() - CX, z = b.position().z() - CZ;
            double r = Math.hypot(x, z);
            if (r <= VENT.craterRadiusM() + 1) vertical++;
            if (r < 1e-9) continue;
            sx += x / r;
            sz += z / r;
            n++;
        }
        double resultant = Math.hypot(sx, sz) / n;
        assertTrue(resultant < 0.06, "uniform azimuths: resultant " + resultant);
        // A vertical-mean salvo spreads with a half-normal of launch angles, not half of it straight up.
        assertTrue(vertical < 0.3 * landed.size(), "bombs landing back in the vent: " + vertical + "/" + landed.size());
    }

    @Test
    void stillAirLapilliFalloutIsSymmetric() {
        Setup s = setup(0);
        for (int i = 0; i < 30; i++) {
            s.tephra().proximalFallout(VENT, 1e5, 120, 0, 25, 0.01, 2e-3, 0.064);
            s.engine().step();
        }
        run(s.engine(), 40);
        double[] r = groundResultant(s, 120);
        assertTrue(r[0] < 0.06, "symmetric pile: resultant " + r[0]);
        assertTrue(r[2] > 3 * VENT.craterRadiusM(), "lapilli land beyond the vent: mean radius " + r[2] + " m");
    }

    @Test
    void windDriftsLapilliLittleButAshFar() {
        Setup s = setup(6);
        for (int i = 0; i < 30; i++) {
            s.tephra().proximalFallout(VENT, 1e5, 120, 0, 25, 0.01, 2e-3, 0.064);
            s.engine().step();
        }
        run(s.engine(), 40);
        double[] lapilli = groundResultant(s, 120);
        assertTrue(lapilli[1] > 0, "lapilli drift a little downwind");
        assertTrue(lapilli[0] < 0.6, "but the pile still surrounds the vent: resultant " + lapilli[0]);

        Setup a = setup(6);
        a.tephra().startPhase(new ExplosivePhase(VENT, 2e4, 0.03, 1, 1100, 50, 0, GrainSizeDistribution.of(1e-6, 1e-6, 0.3, 0.7)));
        run(a.engine(), 20 * 30);
        a.tephra().stopPhase();
        run(a.engine(), 20 * 3600); // medium ash takes most of an hour to fall from a ~3 km column
        double[] ash = gridResultant(a.tephra(), 6000);
        assertTrue(ash[0] > lapilli[0] + 0.2, "fine ash is carried downwind: " + ash[0] + " vs lapilli " + lapilli[0]);
    }

    @Test
    void ventColumnSitsInTheMiddleOfAnAshCell() {
        AshGrid grid = AshGrid.centeredOn(VENT.position(), 8, 1.0, 16);
        int x = (int) Math.floor(VENT.position().x()), z = (int) Math.floor(VENT.position().z());
        int cell = grid.cellAt(x, z);
        // The vent's cell spans columns x−4 … x+3 (centre within half a column of the vent centre), with
        // its neighbours on either side; before, the vent sat on the corner of four cells.
        assertEquals(cell, grid.cellAt(x - 4, z));
        assertEquals(cell, grid.cellAt(x + 3, z));
        assertEquals(cell - 1, grid.cellAt(x - 5, z));
        assertEquals(cell + 1, grid.cellAt(x + 4, z));
    }
}
