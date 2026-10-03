package me.alex4386.typhon.engine.tephra;

import static me.alex4386.typhon.engine.tephra.TephraTestSupport.events;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.tephra.TephraEvents.AshFall;
import me.alex4386.typhon.engine.tephra.TephraEvents.PlumeColumn;
import me.alex4386.typhon.engine.tephra.TephraEvents.VolcanicLightning;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

class AshTest {
    private static final VentSite VENT = VentSite.crater("summit", new BlockPos(0, 100, 0), 4);

    @Test
    void plumeHeightFollowsMastinAndIsMonotone() {
        assertEquals(2000, PlumeModel.realHeightMeters(1.0), 1e-9);
        assertEquals(10.0, PlumeModel.volumeRateForHeight(PlumeModel.realHeightMeters(10.0)), 1e-9);

        TephraConfig config = new TephraConfig();
        double previous = 0;
        for (double mer = 1e2; mer <= 1e8; mer *= 10) {
            double h = PlumeModel.minecraftHeight(mer, 100, config);
            assertTrue(h > previous, "height must grow with eruption rate at " + mer);
            previous = h;
        }
        // Capped at the world top.
        assertEquals(config.worldTopY - 100, PlumeModel.minecraftHeight(1e12, 100, config));
        // Vulcanian 1e5 kg/s ≈ 4.9 km real → ≈ 49 blocks.
        assertEquals(48.7, PlumeModel.minecraftHeight(1e5, 0, config), 0.5);
    }

    private static TephraConfig smallGrid() {
        TephraConfig config = new TephraConfig();
        config.gridCells = 80;
        return config;
    }

    private static List<EngineFrame> run(Engine engine, int ticks) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.tick());
        return frames;
    }

    /** Pure ash (no bombs) Vulcanian phase for {@code seconds}, then {@code settleSeconds} more. */
    private static TephraSubsystem erupt(TerrainModel terrain, int seconds, int settleSeconds, List<EngineFrame> frames) {
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, smallGrid());
        Engine engine = Engine.builder(9).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(22, 99));
        tephra.setWind(6, 0, 0); // towards +X
        ExplosivePhase base = ExplosivePhase.vulcanian(VENT, 1e5);
        tephra.startPhase(new ExplosivePhase(VENT, base.massEruptionRate(), base.gasFraction(), base.overpressureMPa(),
                base.temperatureC(), base.silicaWt(), 0, base.grainSize()));
        frames.addAll(run(engine, seconds * 20));
        tephra.stopPhase();
        frames.addAll(run(engine, settleSeconds * 20));
        return tephra;
    }

    @Test
    void depositIsSkewedDownwindAndThinsWithDistance() {
        TerrainModel terrain = new TerrainModel();
        List<EngineFrame> frames = new ArrayList<>();
        TephraSubsystem tephra = erupt(terrain, 120, 120, frames);

        double upwind = tephra.depositThickness(-80, 0);
        double downwind = tephra.depositThickness(80, 0);
        double crosswind = tephra.depositThickness(0, 80);
        double near = tephra.depositThickness(40, 0);
        double far = tephra.depositThickness(240, 0);
        assertTrue(downwind > 2 * upwind, "downwind " + downwind + " vs upwind " + upwind);
        assertTrue(downwind > crosswind, "downwind " + downwind + " vs crosswind " + crosswind);
        assertTrue(near > far && far > 0, "near " + near + " far " + far);

        // Deposits became blocks: covers near the vent, palette blocks somewhere downwind.
        List<BlockChange> changes = new ArrayList<>();
        frames.forEach(f -> changes.addAll(f.blockChanges()));
        assertFalse(changes.isEmpty());
        AshPalette palette = AshPalette.defaults();
        assertTrue(changes.stream().allMatch(c -> palette.wholeBlock().equals(c.to().id())
                || palette.covers().stream().anyMatch(cover -> cover.block().equals(c.to().id()))));
        assertTrue(changes.stream().allMatch(BlockChange::isConditional), "ash edits are compare-and-set");
    }

    @Test
    void massIsAccountedFor() {
        TerrainModel terrain = new TerrainModel();
        TephraSubsystem tephra = erupt(terrain, 60, 300, new ArrayList<>());
        TephraSubsystem.MassBudget budget = tephra.massBudget();
        double expectedEmitted = 1e5 * 60;
        assertEquals(expectedEmitted, budget.emitted(), expectedEmitted * 1e-9);
        assertTrue(budget.deposited() <= budget.emitted());
        assertTrue(budget.deposited() > 0.3 * budget.emitted(), "most coarse ash lands in the domain");
        double sum = budget.airborne() + budget.deposited() + budget.exported() + budget.discarded();
        assertEquals(budget.emitted(), sum, budget.emitted() * 1e-9);
    }

    @Test
    void plumeLightningAndAshFallEventsAreEmitted() {
        TerrainModel terrain = new TerrainModel();
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, smallGrid());
        Engine engine = Engine.builder(3).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(22, 99));
        tephra.startPhase(ExplosivePhase.plinian(VENT, 1e7));
        List<EngineFrame> frames = run(engine, 20 * 60);

        List<PlumeColumn> columns = events(frames, PlumeColumn.class);
        assertFalse(columns.isEmpty());
        PlumeColumn column = columns.get(columns.size() - 1);
        assertEquals(101 + (int) Math.round(tephra.plumeHeight()), column.topY());
        assertTrue(column.topY() > 200, "Plinian column top " + column.topY());

        List<VolcanicLightning> flashes = events(frames, VolcanicLightning.class);
        assertTrue(flashes.size() > 20, "expected frequent lightning, got " + flashes.size());
        assertTrue(flashes.stream().allMatch(f -> f.position().y() > 101 && f.position().y() <= column.topY()));

        List<AshFall> falls = events(frames, AshFall.class);
        assertFalse(falls.isEmpty());
        assertTrue(falls.stream().allMatch(f -> f.fallRate() >= 0 && f.airborneLoad() >= 0));
    }

    @Test
    void wholeBlockDepositsRaiseTheGroundAndThinOnesCover() {
        TerrainModel terrain = new TerrainModel();
        terrain.apply(TephraTestSupport.flat(2, 63));
        TephraConfig config = new TephraConfig();
        config.depositJitter = 0;
        AshGrid grid = new AshGrid(0, 0, 8, 4);
        double cellArea = 64;

        grid.addDeposit(grid.index(0, 0), 2.5 * config.depositBulkDensity * cellArea); // 2.5 m
        grid.addDeposit(grid.index(1, 0), 0.15 * config.depositBulkDensity * cellArea); // 15 cm
        Outbox outbox = new Outbox();
        grid.applyDeposits(terrain, outbox, config);
        List<BlockChange> changes = outbox.drain(0).blockChanges();

        BlockId tuff = BlockId.minecraft("tuff");
        BlockId gravel = BlockId.minecraft("gravel");
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                TerrainColumn thick = terrain.column(x, z);
                assertEquals(65, thick.groundY(), "two whole blocks at " + x + "," + z);
                assertEquals(tuff, thick.surface());
                TerrainColumn thin = terrain.column(x + 8, z);
                assertEquals(63, thin.groundY());
                assertEquals(gravel, thin.surface());
            }
        }
        assertTrue(changes.contains(BlockChange.replace(new BlockPos(0, 64, 0), BlockId.AIR, tuff)));
        assertTrue(changes.contains(BlockChange.replace(new BlockPos(0, 65, 0), BlockId.AIR, tuff)));
        assertTrue(changes.contains(BlockChange.replace(new BlockPos(8, 63, 0), TephraTestSupport.GRASS, gravel)));
        assertEquals(64 * 2 + 64, changes.size());

        // Re-applying with no new deposit changes nothing.
        grid.applyDeposits(terrain, outbox, config);
        assertTrue(outbox.drain(1).blockChanges().isEmpty());

        // Growing the thin deposit upgrades the cover in place (CAS against the previous cover).
        grid.addDeposit(grid.index(1, 0), 0.30 * config.depositBulkDensity * cellArea);
        grid.applyDeposits(terrain, outbox, config);
        BlockId powder = BlockId.minecraft("light_gray_concrete_powder");
        assertTrue(outbox.drain(2).blockChanges().contains(BlockChange.replace(new BlockPos(8, 63, 0), gravel, powder)));
    }

    @Test
    void depositJitterBreaksUpCellEdgesDeterministically() {
        TerrainModel terrain = new TerrainModel();
        terrain.apply(TephraTestSupport.flat(2, 63));
        TephraConfig config = new TephraConfig();
        AshGrid grid = new AshGrid(0, 0, 8, 2);
        // Just around the 10 cm gravel threshold: with ±30% jitter some columns reach it, some don't.
        grid.addDeposit(grid.index(0, 0), 0.10 * config.depositBulkDensity * 64);
        Outbox outbox = new Outbox();
        grid.applyDeposits(terrain, outbox, config);
        long gravel = outbox.drain(0).blockChanges().stream()
                .filter(c -> c.to().id().equals(BlockId.minecraft("gravel"))).count();
        assertTrue(gravel > 5 && gravel < 59, "gravel columns " + gravel);
        assertEquals(AshGrid.columnNoise(3, -7), AshGrid.columnNoise(3, -7));
    }

    @Test
    void transportConservesMassAwayFromEdges() {
        AshGrid grid = new AshGrid(-128, -128, 8, 32);
        grid.inject(1000, GrainSizeDistribution.VULCANIAN.fractions(), 0, 0, 8);
        double before = grid.airborneTotal();
        assertEquals(1000, before + grid.exported, 1e-9);
        for (int i = 0; i < 5; i++) grid.transport(1, new Vec3d(3, 0, -2), 20);
        assertEquals(1000, grid.airborneTotal() + grid.exported, 1e-9);
        for (double[] layer : grid.airborne) for (double m : layer) assertTrue(m >= 0);
    }
}
