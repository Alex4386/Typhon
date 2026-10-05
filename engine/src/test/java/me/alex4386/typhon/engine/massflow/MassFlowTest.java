package me.alex4386.typhon.engine.massflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.LaharStarted;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.PdcDeposit;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.PdcFront;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.PdcStarted;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.PdcSteam;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.TerrainNeeded;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.world.BlockState;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class MassFlowTest {
    private static final IntBinaryOperator RAMP = MassFlowTestWorld.rampToPlain(0.4, 60, 64);
    private static final BlockPos TOP = new BlockPos(8, 0, 32);

    private static MassFlowTestWorld rampWorld() {
        return new MassFlowTestWorld(0, 0, 15, 3, RAMP);
    }

    private static PyroclasticFlows pdc(MassFlowTestWorld w) {
        return new PyroclasticFlows("pdc", w.terrain);
    }

    private static PyroclasticFlows pdc(MassFlowTestWorld w, double mu) {
        MassFlowConfig c = MassFlowConfig.pdc();
        c.frictionCoefficient = mu;
        return new PyroclasticFlows("pdc", w.terrain, c);
    }

    /** Runs to rest and returns the farthest x reached. */
    private static int runout(MassFlowTestWorld w, MassFlowField f) {
        Engine e = w.engine(f, 1);
        w.runUntilStill(e, f, 6000);
        assertEquals(0, f.activeCellCount(), "flow should come to rest");
        return w.reachX(f);
    }

    // ── Conservation ──

    @Test
    void conservesMassEveryStep() {
        for (MassFlowKind kind : MassFlowKind.values()) {
            MassFlowTestWorld w = rampWorld();
            MassFlowField field = kind == MassFlowKind.PDC ? pdc(w) : new Lahars("lahar", w.terrain);
            if (field instanceof Lahars l) {
                for (int x = 10; x < 50; x++) l.addErodibleDeposit(x, 32, 0.5); // exercise entrainment
            }
            Engine e = w.engine(field, 3);
            field.release(TOP, 3, 2000, 600, kind == MassFlowKind.LAHAR ? 0.3 : 0, Trigger.MANUAL);
            field.addSource(FlowSource.at("feed", new BlockPos(4, 0, 32), 20, 600, kind == MassFlowKind.LAHAR ? 0.2 : 0),
                    Trigger.MANUAL);
            for (int t = 0; t < 600; t += 10) {
                w.run(e, 10);
                var b = field.massBudget();
                assertEquals(0, b.imbalance(), 1e-8 * (b.released() + b.entrained()), kind + " t=" + t + " " + b);
            }
            assertTrue(field.massBudget().deposited() > 0, kind + " should deposit");
        }
    }

    @Test
    void depositVolumeMatchesSettledFlow() {
        MassFlowTestWorld w = rampWorld();
        PyroclasticFlows f = pdc(w);
        f.release(TOP, 3, 3000, 600, 0, Trigger.MANUAL);
        runout(w, f);
        double factor = MassFlowConfig.pdc().depositThicknessFactor;
        assertEquals(3000 * factor, w.depositVolume(f, 1), 1e-6 * 3000);
    }

    // ── Dynamics ──

    @Test
    void flowsDownhillAndChannelisesIntoValley() {
        // Same down-slope gradient, with and without a V-shaped valley (sides rising 0.6 per block).
        double[] valley = depositCentroid((x, z) -> 120 - (int) Math.round(0.35 * x) + (int) Math.round(0.6 * Math.abs(z - 32)));
        double[] open = depositCentroid((x, z) -> 120 - (int) Math.round(0.35 * x));

        assertTrue(valley[0] > 10 + 20, "centroid moved downhill: " + valley[0]);
        assertTrue(valley[1] < 0.5 * open[1], "valley confines the flow: lateral spread " + valley[1] + " vs " + open[1]);
    }

    /** Releases a PDC at (10, 32) and returns the deposit's x centroid and mean |z − 32|. */
    private static double[] depositCentroid(IntBinaryOperator ground) {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 11, 3, ground);
        PyroclasticFlows f = pdc(w);
        f.release(new BlockPos(10, 0, 32), 4, 3000, 600, 0, Trigger.MANUAL);
        runout(w, f);
        double sum = 0, sx = 0, sz = 0;
        for (int x = 0; x < 192; x++) {
            for (int z = 0; z < 64; z++) {
                double d = f.depositThickness(x, z);
                sum += d;
                sx += d * x;
                sz += d * Math.abs(z - 32);
            }
        }
        return new double[] {sx / sum, sz / sum};
    }

    @Test
    void runoutGrowsWithLowerFrictionAndLargerVolume() {
        MassFlowTestWorld rough = rampWorld();
        PyroclasticFlows roughFlow = pdc(rough, 0.25);
        roughFlow.release(TOP, 3, 3000, 600, 0, Trigger.MANUAL);
        MassFlowTestWorld smooth = rampWorld();
        PyroclasticFlows smoothFlow = pdc(smooth, 0.10);
        smoothFlow.release(TOP, 3, 3000, 600, 0, Trigger.MANUAL);
        assertTrue(runout(smooth, smoothFlow) > runout(rough, roughFlow), "lower μ runs farther");

        MassFlowTestWorld small = rampWorld();
        PyroclasticFlows smallFlow = pdc(small);
        smallFlow.release(TOP, 3, 300, 600, 0, Trigger.MANUAL);
        MassFlowTestWorld large = rampWorld();
        PyroclasticFlows largeFlow = pdc(large);
        largeFlow.release(TOP, 3, 3000, 600, 0, Trigger.MANUAL);
        assertTrue(runout(large, largeFlow) > runout(small, smallFlow), "larger volume runs farther");
    }

    @Test
    void pdcComesToRestOnFlatGroundAndDeposits() {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 7, 7, (x, z) -> 64);
        PyroclasticFlows f = pdc(w);
        BlockPos center = new BlockPos(64, 0, 64);
        f.release(center, 6, 500, 600, 0, Trigger.MANUAL); // ~4.4 m deep pile
        runout(w, f);
        assertTrue(w.depositVolume(f, 1) > 0);
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                if (f.depositThickness(x, z) > 0) {
                    assertTrue(Math.hypot(x - 64, z - 64) < 40, "spread stays local on flat ground");
                }
            }
        }
        assertFalse(w.events(PdcDeposit.class).isEmpty());
    }

    @Test
    void laharsRunFartherThanPdcsOfEqualVolume() {
        MassFlowTestWorld pw = rampWorld();
        PyroclasticFlows p = pdc(pw);
        p.release(TOP, 3, 3000, 600, 0, Trigger.MANUAL);
        MassFlowTestWorld lw = rampWorld();
        Lahars l = new Lahars("lahar", lw.terrain);
        l.release(TOP, 3, 3000, 15, 0.3, Trigger.MANUAL);
        assertTrue(runout(lw, l) > runout(pw, p), "lahars are more mobile");
    }

    @Test
    void overtopsALowRidgeButNotAHighOne() {
        assertTrue(beyondRidge(1) > 0, "a 1 m ridge is overtopped");
        assertEquals(0, beyondRidge(25), 0, "a 25 m ridge exceeds the flow's energy line");
    }

    private static double beyondRidge(int height) {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 11, 3, (x, z) -> {
            if (x < 50) return 64 + (int) Math.round((50 - x) * 0.5);
            if (x >= 62 && x < 66) return 64 + height;
            return 64;
        });
        PyroclasticFlows f = pdc(w);
        f.release(new BlockPos(6, 0, 32), 3, 1500, 600, 0, Trigger.MANUAL);
        runout(w, f);
        double beyond = 0;
        for (int x = 66; x < 192; x++) {
            for (int z = 0; z < 64; z++) beyond += f.depositThickness(x, z);
        }
        return beyond;
    }

    // ── Deposits ──

    @Test
    void depositsRaiseTerrainWithCompareAndSetBlocks() {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 3, 3, (x, z) -> 64);
        PyroclasticFlows f = pdc(w);
        f.release(new BlockPos(32, 0, 32), 6, 2000, 400, 0, Trigger.MANUAL);
        runout(w, f);

        Map<BlockPos, BlockState> blocks = w.appliedBlocks();
        int raised = 0;
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int g = w.terrain.column(x, z).groundY();
                if (g > 64) {
                    raised++;
                    BlockState top = blocks.get(new BlockPos(x, g, z));
                    assertTrue(top.equals(MassFlowPalette.TUFF) || top.equals(MassFlowPalette.PDC_VENEER),
                            "top of the deposit at " + x + "," + z + ": " + top);
                }
            }
        }
        assertTrue(raised > 0, "deposit should raise the ground");
        for (BlockChange change : w.blockChanges()) {
            assertTrue(change.isConditional(), "every edit is compare-and-set: " + change);
        }
    }

    @Test
    void hotDepositsWeld() {
        assertTrue(depositedBlocks(750).contains(MassFlowPalette.WELDED_TUFF));
        List<BlockState> cool = depositedBlocks(300);
        assertTrue(cool.contains(MassFlowPalette.TUFF));
        assertFalse(cool.contains(MassFlowPalette.WELDED_TUFF));
    }

    private static List<BlockState> depositedBlocks(double temperature) {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 3, 3, (x, z) -> 64);
        PyroclasticFlows f = pdc(w);
        f.release(new BlockPos(32, 0, 32), 4, 1500, temperature, 0, Trigger.MANUAL);
        runout(w, f);
        return w.blockChanges().stream().map(BlockChange::to).toList();
    }

    // ── Water ──

    @Test
    void pdcOverWaterFlashesSteamAndLosesMass() {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 15, 3, RAMP, (x, z) -> x >= 60 ? 70 : MassFlowTestWorld.NO_WATER);
        PyroclasticFlows f = pdc(w);
        f.release(TOP, 3, 3000, 600, 0, Trigger.MANUAL);
        runout(w, f);
        assertFalse(w.events(PdcSteam.class).isEmpty());
        assertTrue(f.massBudget().lost() > 0);
        assertEquals(0, f.massBudget().imbalance(), 1e-6);
    }

    // ── Lahar triggers ──

    @Test
    void rainSaturatesLooseDepositUntilItFailsAsALahar() {
        MassFlowTestWorld w = rampWorld();
        MassFlowConfig config = MassFlowConfig.lahar();
        config.timeScale = 10; // 10 simulated seconds per engine second
        Lahars l = new Lahars("lahar", w.terrain, config);
        Engine e = w.engine(l, 1);
        // 0.2 m of tephra with 35% porosity holds 70 mm of water: ~35 min of 120 mm/h rain.
        for (int x = 0; x < 40; x++) {
            for (int z = 24; z < 40; z++) l.addErodibleDeposit(x, z, 0.2);
        }
        l.addErodibleDeposit(120, 32, 0.2); // on the flat plain: stays put
        l.setRainfall(120);

        w.run(e, 20 * 180); // 30 min
        assertTrue(w.events(LaharStarted.class).isEmpty(), "pores not full yet");
        w.run(e, 20 * 120); // +20 min

        List<LaharStarted> started = w.events(LaharStarted.class);
        assertEquals(1, started.size());
        assertEquals(Trigger.RAIN, started.get(0).trigger());
        // the tephra failed; what is left is the thin lahar deposit the passing flow laid down
        // (itself loose and erodible), not the 0.2 m fall layer
        assertTrue(l.erodibleThickness(20, 32) < 0.05, "slope deposit failed: " + l.erodibleThickness(20, 32));
        // the world model stores layer tops as float metres
        assertEquals(0.2, l.erodibleThickness(120, 32), 1e-5, "flat deposit stays");
        assertTrue(l.massBudget().entrained() > 0);
        assertTrue(w.reachX(l) > 60, "lahar reaches the plain");
    }

    @Test
    void meltwaterBulksUpOverLooseDeposit() {
        MassFlowTestWorld w = rampWorld();
        Lahars l = new Lahars("lahar", w.terrain);
        Engine e = w.engine(l, 1);
        for (int x = 10; x < 60; x++) {
            for (int z = 20; z < 44; z++) l.addErodibleDeposit(x, z, 1.0);
        }
        l.meltwater(TOP, 3, 3000);
        w.run(e, 200);
        assertEquals(Trigger.MELTWATER, w.events(LaharStarted.class).get(0).trigger());
        assertTrue(l.massBudget().entrained() > 0, "fast flow erodes its bed");
        double maxSediment = 0;
        for (int x = 0; x < 256; x++) {
            for (int z = 0; z < 64; z++) maxSediment = Math.max(maxSediment, l.sedimentFraction(x, z));
        }
        assertTrue(maxSediment > 0.05, "bulked beyond its initial sediment: " + maxSediment);
    }

    @Test
    void buriedMudCompacts() {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 3, 3, (x, z) -> 64);
        Lahars l = new Lahars("lahar", w.terrain);
        l.release(new BlockPos(32, 0, 32), 1, 3000, 15, 0.55, Trigger.MANUAL);
        runout(w, l);
        List<BlockState> placed = w.blockChanges().stream().map(BlockChange::to).toList();
        assertTrue(placed.contains(MassFlowPalette.MUD) || placed.contains(MassFlowPalette.GRAVEL));
        if (w.terrain.column(32, 32).groundY() >= 66) {
            assertTrue(placed.contains(MassFlowPalette.PACKED_MUD), "lower mud compacts under later deposit");
        }
    }

    // ── Sources, events, commands ──

    @Test
    void columnCollapseFeedsAPdc() {
        MassFlowTestWorld w = rampWorld();
        PyroclasticFlows f = pdc(w);
        Engine e = w.engine(f, 1);
        String id = f.columnCollapse("vent", new BlockPos(8, 0, 32), 2, 2e5, 1.0, 650);
        w.run(e, 200);
        f.removeSource(id);
        w.runUntilStill(e, f, 6000);

        PdcStarted started = w.events(PdcStarted.class).get(0);
        assertEquals(Trigger.COLUMN_COLLAPSE, started.trigger());
        assertEquals(200, started.rateM3PerS(), 1e-9);
        assertTrue(w.depositVolume(f, 1) > 0);
        List<PdcFront> fronts = w.events(PdcFront.class);
        assertFalse(fronts.isEmpty());
        assertTrue(fronts.get(0).maxTemperatureC() > 300);
        assertTrue(fronts.stream().allMatch(fr -> fr.cells().size() <= MassFlowConfig.pdc().maxReportedCells));
    }

    @Test
    void columnCollapseCriterionFollowsWoods() {
        assertTrue(ColumnCollapse.analyze(1e9, 150, 0.03, 900).collapses(), "huge, slow, gas-poor column collapses");
        assertFalse(ColumnCollapse.analyze(1e6, 150, 0.03, 900).collapses(), "small column rises buoyantly");
        assertFalse(ColumnCollapse.analyze(1e9, 300, 0.03, 900).collapses(), "faster jet entrains enough air");
        assertFalse(ColumnCollapse.analyze(1e9, 150, 0.06, 900).collapses(), "more gas makes it buoyant");
    }

    @Test
    void commandsAreRoutedByTarget() {
        MassFlowTestWorld w = rampWorld();
        PyroclasticFlows a = new PyroclasticFlows("pdc:a", w.terrain);
        Lahars b = new Lahars("lahar:b", w.terrain);
        Engine e = Engine.builder(1).add(w.terrain).add(a).add(b).build();
        e.submit(new MassFlowCommands.ReleaseFlow("lahar:b", TOP, 2, 500, 15, 0.2, Trigger.LAKE_BREAKOUT));
        e.submit(new MassFlowCommands.SetRainfall("lahar:b", 10));
        w.run(e, 4);
        assertEquals(0, a.massBudget().released());
        assertEquals(500, b.massBudget().released(), 1e-9);
        assertEquals(10, b.rainfall());
        assertEquals(Trigger.LAKE_BREAKOUT, w.events(LaharStarted.class).get(0).trigger());
    }

    @Test
    void unknownTerrainIsAWallAndIsRequested() {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 1, 1, (x, z) -> 100 - x); // drops toward x = 32
        PyroclasticFlows f = pdc(w);
        Engine e = w.engine(f, 1);
        f.release(new BlockPos(10, 0, 16), 2, 800, 600, 0, Trigger.MANUAL);
        w.runUntilStill(e, f, 4000);

        List<TerrainNeeded> needed = w.events(TerrainNeeded.class);
        assertFalse(needed.isEmpty());
        assertTrue(needed.stream().flatMap(n -> n.chunks().stream()).anyMatch(c -> c.x() == 2));
        assertTrue(w.reachX(f) <= 31, "nothing flows into unknown terrain");
        assertEquals(0, f.massBudget().imbalance(), 1e-8);
    }

    // ── Determinism & persistence ──

    @Test
    void sameSeedSameFrames() {
        assertEquals(scenario(), scenario());
    }

    private static List<EngineFrame> scenario() {
        MassFlowTestWorld w = rampWorld();
        Lahars l = new Lahars("lahar", w.terrain);
        Engine e = w.engine(l, 42);
        for (int x = 0; x < 30; x++) l.addErodibleDeposit(x, 32, 0.3);
        l.release(TOP, 3, 1500, 15, 0.3, Trigger.MANUAL);
        w.run(e, 400);
        return w.frames;
    }

    @Test
    void saveRestoreIsBitForBit() {
        int before = 120;
        int after = 400;

        MassFlowTestWorld ref = rampWorld();
        PyroclasticFlows refFlow = pdc(ref);
        Engine refEngine = ref.engine(refFlow, 7);
        refFlow.release(TOP, 3, 2500, 650, 0, Trigger.MANUAL);
        refFlow.addSource(FlowSource.at("feed", new BlockPos(5, 0, 30), 15, 600, 0), Trigger.COLUMN_COLLAPSE);
        ref.run(refEngine, before + after);

        MassFlowTestWorld first = rampWorld();
        PyroclasticFlows firstFlow = pdc(first);
        Engine firstEngine = first.engine(firstFlow, 7);
        firstFlow.release(TOP, 3, 2500, 650, 0, Trigger.MANUAL);
        firstFlow.addSource(FlowSource.at("feed", new BlockPos(5, 0, 30), 15, 600, 0), Trigger.COLUMN_COLLAPSE);
        first.run(firstEngine, before);
        assertTrue(firstFlow.activeCellCount() > 0, "save mid-flow");
        InMemorySaveStore saved = Saves.save(firstEngine);

        MassFlowTestWorld second = rampWorld();
        second.terrain.apply(first.resample()); // host re-sends live terrain after restart
        PyroclasticFlows secondFlow = pdc(second);
        Engine secondEngine = Engine.builder(7)
                .add(second.terrain)
                .add(secondFlow)
                .restore(saved)
                .build();
        second.run(secondEngine, after);

        assertEquals(ref.frames.subList(before, before + after), second.frames);
        assertEquals(refFlow.massBudget(), secondFlow.massBudget());
    }
}
