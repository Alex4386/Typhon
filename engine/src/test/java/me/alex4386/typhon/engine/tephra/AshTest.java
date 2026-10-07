package me.alex4386.typhon.engine.tephra;

import static me.alex4386.typhon.engine.tephra.TephraTestSupport.events;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.tephra.TephraEvents.AshFall;
import me.alex4386.typhon.engine.tephra.TephraEvents.PlumeColumn;
import me.alex4386.typhon.engine.tephra.TephraEvents.VolcanicLightning;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitTable;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

class AshTest {
    private static final VentSite VENT = VentSite.crater("summit", new Point3(5, 100, 5), 40);
    /** One-second engine steps: the ash grid steps once a second anyway. */
    private static final long SECOND = 1_000_000;

    @Test
    void plumeHeightFollowsMastinAndIsMonotone() {
        assertEquals(2000, PlumeModel.realHeightMeters(1.0), 1e-9);
        assertEquals(10.0, PlumeModel.volumeRateForHeight(PlumeModel.realHeightMeters(10.0)), 1e-9);

        double previous = 0;
        for (double mer = 1e2; mer <= 1e8; mer *= 10) {
            double h = PlumeModel.heightForMassRate(mer);
            assertTrue(h > previous, "height must grow with eruption rate at " + mer);
            previous = h;
        }
        // Vulcanian 1e5 kg/s (40 m³/s DRE) ≈ 4.9 km; Plinian 1e8 kg/s ≈ 26 km.
        assertEquals(4870, PlumeModel.heightForMassRate(1e5), 50);
        assertTrue(PlumeModel.heightForMassRate(1e8) > 20_000);
    }

    /** An 8 km ash grid (80 cells of 100 m). */
    private static TephraConfig smallGrid() {
        return grid(80);
    }

    private static TephraConfig grid(int cells) {
        TephraConfig config = new TephraConfig();
        config.gridCells = cells;
        return config;
    }

    private static List<EngineFrame> run(Engine engine, int ticks) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.step());
        return frames;
    }

    /** Pure ash (no bombs) Vulcanian phase for {@code seconds}, then {@code settleSeconds} more, on 100 m ground. */
    private static TephraSubsystem erupt(TerrainModel terrain, TephraConfig config, int seconds, int settleSeconds,
            List<EngineFrame> frames) {
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, config);
        Engine engine = Engine.builder(9).baseStepMicros(SECOND).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(12, 100));
        tephra.setWind(6, 0, 0); // towards +X
        ExplosivePhase base = ExplosivePhase.vulcanian(VENT, 1e5);
        tephra.startPhase(new ExplosivePhase(VENT, base.massEruptionRate(), base.gasFraction(), base.overpressureMPa(),
                base.temperatureC(), base.silicaWt(), 0, base.grainSize()));
        frames.addAll(run(engine, seconds));
        tephra.stopPhase();
        frames.addAll(run(engine, settleSeconds));
        return tephra;
    }

    @Test
    void depositIsSkewedDownwindAndThinsWithDistance() {
        TerrainModel terrain = new TerrainModel();
        List<EngineFrame> frames = new ArrayList<>();
        // Five minutes at 1e5 kg/s leave well under a millimetre of ash: lay layers from 10 µm on.
        TephraConfig config = grid(160);
        config.depositUpdateThickness = 1e-5;
        TephraSubsystem tephra = erupt(terrain, config, 300, 600, frames);

        // 10 m columns: ±1.5 km from the vent, then 1 km and 7 km downwind.
        double upwind = tephra.depositThickness(-150, 0);
        double downwind = tephra.depositThickness(150, 0);
        double crosswind = tephra.depositThickness(0, 150);
        double near = tephra.depositThickness(100, 0);
        double far = tephra.depositThickness(700, 0);
        assertTrue(downwind > 2 * upwind, "downwind " + downwind + " vs upwind " + upwind);
        assertTrue(downwind > crosswind, "downwind " + downwind + " vs crosswind " + crosswind);
        assertTrue(near > far && far > 0, "near " + near + " far " + far);

        // The fall lies on the ground as ash layers downwind.
        WorldModel world = terrain.world();
        long covered = 0;
        for (int x = 0; x <= 200; x += 10) {
            int n = world.layerCount(x, 0);
            if (n > 0 && world.layer(x, 0, n - 1).materialInfo() == MaterialTable.ASH) covered++;
        }
        assertTrue(covered > 0, "ash layers downwind");
    }

    @Test
    void massIsAccountedFor() {
        TerrainModel terrain = new TerrainModel();
        TephraSubsystem tephra = erupt(terrain, grid(160), 60, 1800, new ArrayList<>());
        TephraSubsystem.MassBudget budget = tephra.massBudget();
        double expectedEmitted = 1e5 * 60;
        assertEquals(expectedEmitted, budget.emitted(), expectedEmitted * 1e-9);
        assertTrue(budget.deposited() <= budget.emitted());
        // From a ~5 km column, lapilli and coarse ash fall out within half an hour; fine ash drifts away.
        assertTrue(budget.deposited() > 0.2 * budget.emitted(), "coarse tephra lands in the domain");
        double sum = budget.airborne() + budget.deposited() + budget.exported() + budget.discarded();
        assertEquals(budget.emitted(), sum, budget.emitted() * 1e-9);
    }

    @Test
    void plumeLightningAndAshFallEventsAreEmitted() {
        TerrainModel terrain = new TerrainModel();
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, smallGrid());
        Engine engine = Engine.builder(3).baseStepMicros(SECOND).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(4, 100));
        tephra.startPhase(ExplosivePhase.plinian(VENT, 1e7));
        List<EngineFrame> frames = run(engine, 60);

        List<PlumeColumn> columns = events(frames, PlumeColumn.class);
        assertFalse(columns.isEmpty());
        PlumeColumn column = columns.get(columns.size() - 1);
        assertEquals(VENT.position().y() + tephra.plumeHeight(), column.topZ(), 1e-6);
        assertTrue(column.topZ() > 10_000, "Plinian column top " + column.topZ());

        List<VolcanicLightning> flashes = events(frames, VolcanicLightning.class);
        assertTrue(flashes.size() > 20, "expected frequent lightning, got " + flashes.size());
        assertTrue(flashes.stream().allMatch(f -> f.position().y() > VENT.position().y() && f.position().y() <= column.topZ()));

        List<AshFall> falls = events(frames, AshFall.class);
        assertFalse(falls.isEmpty());
        assertTrue(falls.stream().allMatch(f -> f.fallRate() >= 0 && f.airborneLoad() >= 0));
    }

    /** Ash volume (m³) above a flat 64 m ground over {@code [-32, 48)²} of 1 m columns (single-precision surfaces: ±0.02 m³). */
    private static double ashVolume(WorldModel world) {
        double sum = 0;
        for (int z = -32; z < 48; z++) for (int x = -32; x < 48; x++) sum += world.surfaceZ(x, z) - 64;
        return sum;
    }

    @Test
    void depositsLayAshOfTheirThickness() {
        TerrainModel terrain = TestGround.terrain(1.0);
        terrain.apply(TephraTestSupport.flat(2, 64));
        WorldModel world = terrain.world();
        TephraConfig config = new TephraConfig();
        AshGrid grid = new AshGrid(0, 0, 8, 1.0, 4);
        double cellArea = 64;
        int unit = UnitTable.UNATTRIBUTED;

        grid.addDeposit(grid.index(0, 0), 2.5 * config.depositBulkDensity * cellArea); // 2.5 m
        grid.addDeposit(grid.index(1, 0), 0.15 * config.depositBulkDensity * cellArea); // 15 cm
        grid.applyDeposits(world, config.depositBulkDensity, config.depositUpdateThickness, unit, (x, z) -> false);

        assertEquals((2.5 + 0.15) * cellArea, ashVolume(world), 0.02, "every kilogram lands as ash");
        // Edges relax to the angle of repose; the cells' interiors keep (about) their thickness.
        assertTrue(world.surfaceZ(3, 3) > 65.5, "the thick cell's interior: " + world.surfaceZ(3, 3));
        assertTrue(world.surfaceZ(13, 3) > 64.1 && world.surfaceZ(13, 3) < world.surfaceZ(3, 3),
                "the thin cell's interior: " + world.surfaceZ(13, 3));
        int n = world.layerCount(13, 3);
        assertEquals(MaterialTable.ASH, world.layer(13, 3, n - 1).materialInfo());

        // Re-applying with no new deposit changes nothing.
        grid.applyDeposits(world, config.depositBulkDensity, config.depositUpdateThickness, unit, (x, z) -> false);
        assertEquals((2.5 + 0.15) * cellArea, ashVolume(world), 0.02);

        // Growing the thin deposit adds only the increment.
        grid.addDeposit(grid.index(1, 0), 0.30 * config.depositBulkDensity * cellArea);
        grid.applyDeposits(world, config.depositBulkDensity, config.depositUpdateThickness, unit, (x, z) -> false);
        assertEquals((2.5 + 0.45) * cellArea, ashVolume(world), 0.02);

        // Molten columns take the ash into the flow: no layer forms there.
        grid.addDeposit(grid.index(2, 0), 0.5 * config.depositBulkDensity * cellArea);
        grid.applyDeposits(world, config.depositBulkDensity, config.depositUpdateThickness, unit, (x, z) -> x >= 16);
        assertEquals((2.5 + 0.45) * cellArea, ashVolume(world), 0.02);
    }

    @Test
    void transportConservesMassAwayFromEdges() {
        AshGrid grid = new AshGrid(-128, -128, 8, 1.0, 32);
        grid.inject(1000, GrainSizeDistribution.VULCANIAN.fractions(), 0, 0, 8);
        double before = grid.airborneTotal();
        assertEquals(1000, before + grid.exported, 1e-9);
        for (int i = 0; i < 5; i++) grid.transport(1, new Vec3d(3, 0, -2), 20);
        assertEquals(1000, grid.airborneTotal() + grid.exported, 1e-9);
        for (double[] layer : grid.airborne) for (double m : layer) assertTrue(m >= 0);
    }

    /** Count of AshFall events over a Vulcanian run with the given aggregation. */
    private static List<AshFall> ashFalls(double changeFraction, double refreshSeconds) {
        TerrainModel terrain = new TerrainModel();
        TephraConfig config = smallGrid();
        config.ashEventChangeFraction = changeFraction;
        config.ashEventRefreshSeconds = refreshSeconds;
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, config);
        Engine engine = Engine.builder(9).baseStepMicros(SECOND).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(4, 100));
        tephra.setWind(6, 0, 0.5);
        tephra.startPhase(ExplosivePhase.vulcanian(VENT, 1e5));
        List<AshFall> falls = new ArrayList<>();
        // Four minutes of eruption, then until the fine ash has settled or drifted out of the grid.
        for (int i = 0; i < 6 * 3600; i++) {
            if (i == 240) tephra.stopPhase();
            // Keep only the ash-fall events: whole frames of a long run would not fit the test heap.
            events(List.of(engine.step()), AshFall.class).forEach(falls::add);
            if (i > 240 && tephra.massBudget().airborne() == 0) {
                for (int k = 0; k < 10; k++) events(List.of(engine.step()), AshFall.class).forEach(falls::add);
                break;
            }
        }
        return falls;
    }

    @Test
    void ashFallEventsAreAggregatedAndRegionsClear() {
        List<AshFall> everyEvaluation = ashFalls(0, 0.05);
        List<AshFall> aggregated = ashFalls(new TephraConfig().ashEventChangeFraction, new TephraConfig().ashEventRefreshSeconds);
        assertTrue(aggregated.size() * 3 < everyEvaluation.size(),
                aggregated.size() + " aggregated vs " + everyEvaluation.size() + " unaggregated");

        // The last event of every region announced after the phase ended and the ash settled is a clear.
        java.util.Map<Point3, AshFall> last = new java.util.LinkedHashMap<>();
        for (AshFall fall : aggregated) last.put(fall.center(), fall);
        assertFalse(last.isEmpty());
        assertTrue(last.values().stream().allMatch(f -> f.fallRate() == 0 && f.airborneLoad() == 0),
                "every reported region is cleared once the ash is gone");
    }

    @Test
    void initialWindComesFromConfig() {
        TephraConfig config = new TephraConfig();
        config.initialWindSpeed = 3.5;
        config.initialWindDirectionRad = 1.2;
        config.initialWindVariability = 0.25;
        TephraSubsystem tephra = new TephraSubsystem("tephra", new TerrainModel(), config);
        assertEquals(3.5, tephra.wind().baseSpeed());
        assertEquals(1.2, tephra.wind().baseDirectionRad());
        assertEquals(0.25, tephra.wind().variability());
    }
}
