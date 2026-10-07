package me.alex4386.typhon.engine.dike;

import static me.alex4386.typhon.engine.dike.DikeTestWorld.basalt;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.cone;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.events;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.fastConfig;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.flat;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.run;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.runUntil;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.world;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import me.alex4386.typhon.engine.deformation.DikeGeometry;
import me.alex4386.typhon.engine.dike.DikeEvents.DikeAdvanced;
import me.alex4386.typhon.engine.dike.DikeEvents.DikeStalled;
import me.alex4386.typhon.engine.dike.DikeEvents.DikeStarted;
import me.alex4386.typhon.engine.dike.DikeEvents.FissureOpened;
import me.alex4386.typhon.engine.dike.DikeEvents.StallReason;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class DikePropagationTest {
    private static final int LONG = 20 * 3600;

    @Test
    void forcedDikeReachesSurfaceAndOpensFissure() {
        DikeTestWorld.World w = world(1, basalt(14).build(), fastConfig(), flat(), null);
        w.engine().submit(new DikeCommands.ForceDike("v"));
        List<EngineFrame> frames = run(w.engine(), LONG);

        assertEquals(1, events(frames, DikeStarted.class).size());
        List<FissureOpened> opened = events(frames, FissureOpened.class);
        assertEquals(1, opened.size(), "basaltic dike with ample pressure should surface");
        VentSite vent = opened.get(0).vent();
        assertEquals(VentKind.FISSURE, vent.kind());
        assertEquals(64, vent.position().y(), "fissure opens at the ground surface");
        assertTrue(vent.fissureLength() >= 3 && vent.fissureLength() <= 60);

        Dike dike = w.dikes().dikes().get(0);
        assertEquals(DikeStatus.ERUPTED, dike.status());
        assertEquals(0, dike.depthM(), 1e-9);
        assertEquals(vent, dike.fissure());
        assertEquals(List.of(vent), w.dikes().openedVents());
        assertEquals(dike.tip().x(), vent.position().x());
        assertEquals(dike.tip().z(), vent.position().z());

        List<DikeAdvanced> advances = events(frames, DikeAdvanced.class);
        assertFalse(advances.isEmpty());
        double previous = Double.MAX_VALUE;
        for (DikeAdvanced a : advances) {
            assertTrue(a.depthM() <= previous, "tip only rises");
            previous = a.depthM();
            assertTrue(a.speedMPerS() >= 0.01 && a.speedMPerS() <= 5, "realistic dike speed: " + a.speedMPerS());
            assertTrue(a.openingM() >= 0.2 && a.openingM() <= 3);
        }
        int hypocenters = advances.stream().mapToInt(a -> a.hypocenters().size()).sum();
        assertTrue(hypocenters > 20, "rising dike should be seismically active: " + hypocenters);
        // hypocentres migrate upward with the tip
        int firstY = advances.stream().filter(a -> !a.hypocenters().isEmpty()).findFirst().orElseThrow()
                .hypocenters().get(0).y();
        DikeAdvanced last = advances.stream().filter(a -> !a.hypocenters().isEmpty()).reduce((a, b) -> b).orElseThrow();
        assertTrue(last.hypocenters().get(0).y() > firstY);
    }

    @Test
    void higherOverpressureRisesFaster() {
        long slow = timeToSurface(9);
        long fast = timeToSurface(14);
        assertTrue(slow > 0 && fast > 0, "both should surface: " + slow + ", " + fast);
        assertTrue(fast < slow, "fast " + fast + " vs slow " + slow);
    }

    private static long timeToSurface(double overpressure) {
        DikeTestWorld.World w = world(3, basalt(overpressure).build(), fastConfig(), flat(), null);
        w.engine().submit(new DikeCommands.ForceDike("v"));
        return runUntil(w.engine(), FissureOpened.class, LONG);
    }

    @Test
    void denseBasaltStallsWithoutEnoughPressure() {
        DikeTestWorld.World w = world(4, basalt(1).build(), fastConfig(), flat(), null);
        w.engine().submit(new DikeCommands.ForceDike("v"));
        List<EngineFrame> frames = run(w.engine(), LONG);

        assertTrue(events(frames, FissureOpened.class).isEmpty());
        List<DikeStalled> stalls = events(frames, DikeStalled.class);
        assertEquals(1, stalls.size());
        assertEquals(StallReason.INSUFFICIENT_PRESSURE, stalls.get(0).reason());
        Dike dike = w.dikes().dikes().get(0);
        assertEquals(DikeStatus.STALLED, dike.status());
        assertTrue(dike.depthM() > 0 && dike.depthM() < 4000, "stalls part-way: " + dike.depthM());
    }

    @Test
    void viscousRhyoliteFreezes() {
        DikeTestWorld.World w = world(5, basalt(14).initialSilicaWt(72).initialTemperatureC(780).build(), fastConfig(),
                flat(), null);
        w.engine().submit(new DikeCommands.ForceDike("v"));
        List<DikeStalled> stalls = events(run(w.engine(), LONG), DikeStalled.class);
        assertEquals(1, stalls.size());
        assertEquals(StallReason.FROZE, stalls.get(0).reason());
    }

    @Test
    void intrudedVolumeComesOutOfTheChamber() {
        DikeTestWorld.World w = world(6, basalt(14).build(), fastConfig(), flat(), null);
        assertEquals(0, w.chamber().gasVolumeFraction(), "dry chamber: constant compressibility");
        double before = w.chamber().overpressureMPa();
        w.engine().submit(new DikeCommands.ForceDike("v"));
        run(w.engine(), LONG);

        double volume = w.dikes().dikes().get(0).volumeM3();
        assertTrue(volume > 1e5, "km-scale dike volume: " + volume);
        double stiffness = w.chamber().config().volume() * w.chamber().effectiveCompressibility();
        assertEquals(volume, (before - w.chamber().overpressureMPa()) * stiffness, volume * 1e-6);
    }

    @Test
    void conePushesDikesTowardTheFlanks() {
        double flat = meanSurfacingDistance(false);
        double cone = meanSurfacingDistance(true);
        assertTrue(cone > flat * 1.5, "cone " + cone + " vs flat " + flat);
    }

    private static double meanSurfacingDistance(boolean onCone) {
        double sum = 0;
        int n = 24;
        for (int seed = 0; seed < n; seed++) {
            DikeTestWorld.World w = world(100 + seed, basalt(14).build(), fastConfig(), onCone ? cone() : flat(), null);
            w.engine().submit(new DikeCommands.ForceDike("v"));
            runUntil(w.engine(), FissureOpened.class, LONG);
            VentSite vent = w.dikes().openedVents().get(0);
            sum += Math.hypot(vent.position().x(), vent.position().z());
        }
        return sum / n;
    }

    @Test
    void fissuresOnAConeAreRadial() {
        DikeTestWorld.World w = world(7, basalt(14).build(), fastConfig(), cone(), null);
        w.engine().submit(new DikeCommands.ForceDike("v"));
        runUntil(w.engine(), FissureOpened.class, LONG);
        VentSite vent = w.dikes().openedVents().get(0);
        double radial = Math.atan2(vent.position().z() + 0.5, vent.position().x() + 0.5);
        double diff = Math.abs(Math.IEEEremainder(vent.fissureAngleRad() - radial, Math.PI));
        assertTrue(diff < 0.2, "strike should be radial, off by " + diff);
    }

    @Test
    void nucleationNeedsPressureAndASealedConduit() {
        // how sealed the summit is comes from the chamber's live conduit openness, not a setting
        DikeConfig spontaneous = fastConfig();
        spontaneous.maxInitiationRate = 1.0 / 10;
        java.util.function.BiFunction<Double, Double, me.alex4386.typhon.engine.magma.MagmaChamberConfig> withOpenness =
                (p, o) -> basalt(p).conduit(me.alex4386.typhon.engine.magma.ConduitConfig.DEFAULT.withInitialOpenness(o)).build();

        assertTrue(events(run(world(8, withOpenness.apply(10.0, 0.0), spontaneous, flat(), null).engine(), 20 * 600),
                DikeStarted.class).isEmpty(), "below the initiation threshold");

        assertTrue(events(run(world(8, withOpenness.apply(14.9, 1.0), spontaneous, flat(), null).engine(), 20 * 600),
                DikeStarted.class).isEmpty(), "an open conduit vents at the summit instead");

        assertFalse(events(run(world(8, withOpenness.apply(14.9, 0.0), spontaneous, flat(), null).engine(), 20 * 600),
                DikeStarted.class).isEmpty(), "near failure with a sealed conduit");
    }

    @Test
    void geometriesDescribeTheIntrusion() {
        DikeTestWorld.World w = world(9, basalt(2).build(), fastConfig(), flat(), null);
        w.engine().submit(new DikeCommands.ForceDike("v"));
        run(w.engine(), LONG);
        List<DikeGeometry> geometries = w.dikes().geometries();
        assertEquals(1, geometries.size());
        DikeGeometry g = geometries.get(0);
        Dike dike = w.dikes().dikes().get(0);
        assertEquals(dike.depthM(), g.topDepthM(), 1e-9);
        assertEquals(4000, g.bottomDepthM(), 1e-9);
        assertEquals(dike.openingM(), g.openingM(), 1e-12);
        assertNotNull(dike.tip());
    }

    @Test
    void sameSeedSameDike() {
        List<EngineFrame> a = forcedRun(11);
        List<EngineFrame> b = forcedRun(11);
        assertEquals(a, b);
    }

    private static List<EngineFrame> forcedRun(long seed) {
        DikeTestWorld.World w = world(seed, basalt(14).build(), fastConfig(), cone(), null);
        w.engine().submit(new DikeCommands.ForceDike("v"));
        return run(w.engine(), 20 * 400);
    }

    @Test
    void saveAndRestoreMidPropagationIsBitForBit() {
        int before = 20 * 10;
        int after = 20 * 600;
        DikeTestWorld.World reference = world(12, basalt(14).build(), fastConfig(), cone(), null);
        reference.engine().submit(new DikeCommands.ForceDike("v"));
        List<EngineFrame> expected = run(reference.engine(), before + after);

        DikeTestWorld.World first = world(12, basalt(14).build(), fastConfig(), cone(), null);
        first.engine().submit(new DikeCommands.ForceDike("v"));
        run(first.engine(), before);
        assertTrue(first.dikes().dikes().get(0).propagating(), "save point should be mid-propagation");
        InMemorySaveStore saved = Saves.save(first.engine());

        DikeTestWorld.World second = world(12, basalt(14).build(), fastConfig(), cone(),
                saved);
        List<EngineFrame> resumed = run(second.engine(), after);
        assertEquals(expected.subList(before, before + after), resumed);
    }
}
