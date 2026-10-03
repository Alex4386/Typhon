package me.alex4386.typhon.engine.tephra;

import static me.alex4386.typhon.engine.tephra.TephraTestSupport.events;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.tephra.TephraCommands.SetWind;
import me.alex4386.typhon.engine.tephra.TephraCommands.StartExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraCommands.StopExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLanded;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLaunched;
import me.alex4386.typhon.engine.tephra.TephraEvents.ExplosivePhaseChanged;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.volcano.VentSite;
import org.junit.jupiter.api.Test;

class TephraSubsystemTest {
    private static final int TERRAIN_RADIUS = 20;
    private static final VentSite VENT = VentSite.crater("summit", new BlockPos(0, 80, 0), 3);

    private static TephraConfig config() {
        TephraConfig config = new TephraConfig();
        config.gridCells = 64;
        return config;
    }

    private static final class Rig {
        final TerrainModel terrain = new TerrainModel();
        final TephraSubsystem tephra = new TephraSubsystem("tephra:test", terrain, config());

        Engine engine(long seed, JsonObject restore) {
            Engine.Builder builder = Engine.builder(seed).add(terrain).add(tephra);
            if (restore != null) builder.restore(restore);
            return builder.build();
        }
    }

    /** Strombolian bombs and ash with veering wind, driven entirely through commands. */
    private static void script(Engine engine, long tick) {
        if (tick == 0) {
            engine.submit(TephraTestSupport.flat(TERRAIN_RADIUS, 79));
            engine.submit(new SetWind("tephra:test", 4, 0.7, 0.8));
            engine.submit(new StartExplosivePhase("tephra:test", ExplosivePhase.strombolian(VENT, 4000)));
        }
        if (tick == 500) engine.submit(new StopExplosivePhase("tephra:test"));
    }

    private static List<EngineFrame> run(Engine engine, int from, int to) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int t = from; t < to; t++) {
            script(engine, t);
            frames.add(engine.tick());
        }
        return frames;
    }

    @Test
    void phaseLaunchesBombsThatLandAndIsDeterministic() {
        Rig a = new Rig();
        List<EngineFrame> first = run(a.engine(42, null), 0, 900);
        Rig b = new Rig();
        List<EngineFrame> second = run(b.engine(42, null), 0, 900);
        assertEquals(first, second);

        List<BombLaunched> launched = events(first, BombLaunched.class);
        List<BombLanded> landed = events(first, BombLanded.class);
        assertTrue(launched.size() > 10, "launched " + launched.size());
        assertEquals(launched.size(), landed.size(), "every bomb lands before the end");
        List<ExplosivePhaseChanged> changes = events(first, ExplosivePhaseChanged.class);
        assertEquals(2, changes.size());
        assertTrue(changes.get(0).active());
        assertFalse(changes.get(1).active());
        assertNull(a.tephra.activePhase());

        // Bombs leave from the crater and land around it, a few tens of blocks away.
        for (BombLaunched l : launched) {
            assertTrue(l.start().subtract(new Vec3d(0.5, 81, 0.5)).horizontalLength() <= 3.0001);
            assertTrue(l.velocity().y() > 0);
        }
        double meanDistance = landed.stream()
                .mapToDouble(l -> Math.hypot(l.position().x(), l.position().z()))
                .average().orElseThrow();
        assertTrue(meanDistance > 5 && meanDistance < 300, "mean landing distance " + meanDistance);

        Rig c = new Rig();
        assertFalse(first.equals(run(c.engine(43, null), 0, 900)), "different seed, different eruption");
    }

    @Test
    void saveAndRestoreResumesBitForBitIncludingBombsInFlight() {
        Rig reference = new Rig();
        List<EngineFrame> expected = run(reference.engine(7, null), 0, 1000);

        Rig before = new Rig();
        Engine engine = before.engine(7, null);
        run(engine, 0, 300);
        assertTrue(before.tephra.inFlightBombs() > 0, "need bombs in flight at save time");
        String saved = engine.saveState().toString();

        Rig after = new Rig();
        Engine resumed = after.engine(7, JsonParser.parseString(saved).getAsJsonObject());
        // The host re-sends the (engine-modified) terrain after a restart.
        resumed.submit(TephraTestSupport.resample(before.terrain, TERRAIN_RADIUS));
        assertEquals(before.tephra.inFlightBombs(), after.tephra.inFlightBombs());

        assertEquals(expected.subList(300, 1000), run(resumed, 300, 1000));
        assertEquals(reference.tephra.massBudget(), after.tephra.massBudget());
    }

    @Test
    void commandsForOtherTargetsAreIgnored() {
        Rig rig = new Rig();
        Engine engine = rig.engine(1, null);
        engine.submit(new StartExplosivePhase("tephra:elsewhere", ExplosivePhase.strombolian(VENT, 4000)));
        engine.tick();
        assertNull(rig.tephra.activePhase());
    }

    @Test
    void phaseCanBeDerivedFromMagmaState() {
        MagmaState dacite = new MagmaState() {
            @Override public BlockPos chamberCenter() { return new BlockPos(0, -500, 0); }
            @Override public double overpressureMPa() { return 8; }
            @Override public double overpressureRateMPaPerSecond() { return 0; }
            @Override public double temperatureC() { return 850; }
            @Override public double silicaWt() { return 66; }
            @Override public double waterWt() { return 5; }
            @Override public double crystalFraction() { return 0.3; }
            @Override public double eruptionRate() { return 400; }
        };
        ExplosivePhase phase = ExplosivePhase.fromMagma(VENT, dacite, 0.02);
        assertEquals(1e6, phase.massEruptionRate(), 1e-6);
        assertEquals(0.04, phase.gasFraction(), 1e-12);
        double[] fractions = phase.grainSize().fractions();
        double[] basaltic = GrainSizeDistribution.forMagma(49, 0.5).fractions();
        assertTrue(fractions[GrainClass.FINE_ASH.ordinal()] > basaltic[GrainClass.FINE_ASH.ordinal()]);
        assertTrue(fractions[GrainClass.LAPILLI.ordinal()] < basaltic[GrainClass.LAPILLI.ordinal()]);
    }

    @Test
    void windVariesSlowlyAndDeterministically() {
        WindField a = new WindField(10, 0, 0);
        a.set(10, 0, 1, 0.3, 1.1);
        WindField b = new WindField(1, 1, 0);
        b.set(10, 0, 1, 0.3, 1.1);
        assertEquals(a.at(12345), b.at(12345));
        Vec3d t0 = a.at(0), t1 = a.at(20);
        assertTrue(t0.subtract(t1).length() < 0.5, "wind changes slowly");
        assertEquals(0, new WindField(0, 0, 0.5).at(100).length());
        assertEquals(10, new WindField(10, 0, 0).at(999).length(), 1e-12);
    }
}
