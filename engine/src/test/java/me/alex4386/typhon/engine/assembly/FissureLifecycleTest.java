package me.alex4386.typhon.engine.assembly;

import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.CRATER;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.events;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.dike.Dike;
import me.alex4386.typhon.engine.dike.DikeCommands;
import me.alex4386.typhon.engine.dike.DikeEvents;
import me.alex4386.typhon.engine.dike.DikeEvents.FissureOpened;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.magma.MagmaEvents;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionEnded;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.testing.TestConduits;
import me.alex4386.typhon.engine.volcano.VentCommands;
import me.alex4386.typhon.engine.volcano.VentEvents.VentFormed;
import me.alex4386.typhon.engine.volcano.VentEvents.VentStateChanged;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VentStatus;
import org.junit.jupiter.api.Test;

/** Fissures wane and freeze, later eruptions avoid them, and the user can seal, remove and block. */
class FissureLifecycleTest {
    /** Twenty minutes (s): the spans below were written as minutes of play at the old ×20. */
    static final double MINUTE = 1200;

    record World(Engine engine, TerrainModel terrain, VolcanoSystem volcano) {
        VolcanoCoupler coupler() {
            return volcano.coupler();
        }
    }

    /** A pressurised chamber with no conduit: only a dike gets its magma out. */
    static MagmaChamberConfig flank() {
        return MagmaChamberConfig.builder("test", new Point3(0, -3000, 0))
                .initialOverpressureMPa(14.0) // below wall rupture (2 × 15 MPa): no dike of its own
                .supplyVariability(0)
                .build();
    }

    /** {@link #flank()} under a crater whose conduit is still molten (it would fail at 15 MPa). */
    static MagmaChamberConfig flankWithSummitConduit() {
        return flank().toBuilder().conduit(TestConduits.molten()).build();
    }

    static World world(long seed, MagmaChamberConfig chamber, SaveStore restore, int threads) {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder("test", List.of(CRATER), terrain, lava)
                .chamber(chamber)
                .dikesEnabled(true)
                .build();
        Engine.Builder builder = Engine.builder(seed).adaptive(Engine.DEFAULT_MAX_STEP_SECONDS).threads(threads).add(terrain);
        volcano.addTo(builder).add(lava);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        if (restore == null) engine.submit(VolcanoSystemTest.cone());
        return new World(engine, terrain, volcano);
    }

    static List<EngineFrame> run(Engine engine, double seconds) {
        return VolcanoSystemTest.run(engine, seconds);
    }

    /** Forces a dike and runs until its fissure erupts; returns the fissure's id. */
    static String flankEruption(World w, List<EngineFrame> frames) {
        // Only the forced dike: it takes hours to rise, long enough for the chamber (above the nucleation
        // threshold) to start dikes of its own.
        w.engine().submit(new DikeCommands.BlockDikes("test", true));
        w.engine().step();
        w.engine().submit(new DikeCommands.ForceDike("test"));
        for (double end = w.engine().time() + 30 * MINUTE; w.engine().time() < end; ) {
            EngineFrame f = w.engine().step();
            frames.add(f);
            List<FissureOpened> opened = events(List.of(f), FissureOpened.class);
            if (!opened.isEmpty()) {
                frames.addAll(run(w.engine(), 2 * MINUTE));
                assertTrue(w.volcano().chamber().erupting(), "a flank eruption should follow");
                return opened.get(0).vent().id();
            }
        }
        throw new AssertionError("the forced dike never reached the surface");
    }

    static List<VentStateChanged> states(List<EngineFrame> frames, String ventId) {
        List<VentStateChanged> out = new ArrayList<>();
        for (VentStateChanged e : events(frames, VentStateChanged.class)) if (e.ventId().equals(ventId)) out.add(e);
        return out;
    }

    @Test
    void fissureFeederFreezesAfterTheEruptionAndIsNeverReused() {
        World w = world(3, flank(), null, 1);
        List<EngineFrame> frames = new ArrayList<>();
        String fissure = flankEruption(w, frames);
        assertEquals(VentStatus.ACTIVE, w.coupler().ventStatus(fissure));
        assertTrue(w.coupler().ventFluxM3PerS(fissure) > 0);
        assertTrue(w.coupler().feederWidthM(fissure) > 0);

        // The eruption stops: no more heat reaches the feeder and it freezes into the wall rock.
        w.engine().submit(new MagmaCommands.StopEruption("test"));
        frames.addAll(run(w.engine(), 120 * 86_400)); // a dike-fed feeder freezes over months of repose
        assertFalse(w.volcano().chamber().erupting());
        assertEquals(VentStatus.FROZEN, w.coupler().ventStatus(fissure), "the idle feeder should freeze");
        assertTrue(states(frames, fissure).stream().anyMatch(e -> e.current() == VentStatus.FROZEN));
        assertEquals(0, w.coupler().feederWidthM(fissure));

        // The stopped eruption left no molten conduit: forcing the next one sends a new dike up, and the
        // eruption goes through its fissure, never the extinct one.
        assertEquals(0, w.volcano().chamber().conduitOpenness());
        frames.clear();
        w.engine().submit(new MagmaCommands.StartEruption("test"));
        String next = null;
        for (double end = w.engine().time() + 30 * MINUTE; w.engine().time() < end && next == null; ) {
            EngineFrame f = w.engine().step();
            frames.add(f);
            for (FissureOpened o : events(List.of(f), FissureOpened.class)) next = o.vent().id();
        }
        assertNotNull(next, "a forced eruption without a conduit should send a dike up");
        frames.addAll(run(w.engine(), 2 * MINUTE));
        assertTrue(w.volcano().chamber().erupting());
        List<String> active = w.coupler().activeVents().stream().map(VentSite::id).toList();
        assertTrue(active.contains(next) && !active.contains(fissure) && !active.contains(CRATER.id()), active.toString());
        assertEquals(0, w.coupler().ventFluxM3PerS(fissure));
        assertEquals(VentStatus.FROZEN, w.coupler().ventStatus(fissure));
        assertTrue(w.coupler().allVents().stream().anyMatch(v -> v.id().equals(fissure)),
                "the extinct fissure stays as a landform");
    }

    @Test
    void anEruptingFissureLocalisesIntoAVentOnItsLine() {
        World w = world(3, flank(), null, 1);
        List<EngineFrame> frames = new ArrayList<>();
        String fissureId = flankEruption(w, frames);
        VentSite fissure = w.coupler().allVents().stream().filter(v -> v.id().equals(fissureId)).findFirst().orElseThrow();
        assertTrue(events(frames, VentFormed.class).isEmpty(), "a fresh fissure erupts along its length");

        // Narrow segments starve and freeze; a segment that keeps the flow after its neighbours froze is a vent.
        List<VentFormed> formed = new ArrayList<>();
        for (double end = w.engine().time() + 30 * 86_400; w.engine().time() < end && formed.isEmpty()
                && w.volcano().chamber().erupting(); ) {
            formed.addAll(events(List.of(w.engine().step()), VentFormed.class));
        }
        System.out.println("localised after " + (w.engine().time() / 3600) + " h: " + formed.size() + " vents, erupting="
                + w.volcano().chamber().erupting() + ", segments=" + java.util.Arrays.toString(w.coupler().feederSegments(fissureId)));
        assertFalse(formed.isEmpty(), "the flow should localise before the eruption ends");
        VentSite vent = formed.get(0).vent();
        assertEquals(fissureId, formed.get(0).fissureId());
        assertEquals(me.alex4386.typhon.engine.volcano.VentKind.CRATER, vent.kind());
        double dx = vent.position().x() - fissure.position().x();
        double dz = vent.position().z() - fissure.position().z();
        double along = dx * Math.cos(fissure.fissureAngleRad()) + dz * Math.sin(fissure.fissureAngleRad());
        double across = -dx * Math.sin(fissure.fissureAngleRad()) + dz * Math.cos(fissure.fissureAngleRad());
        assertTrue(Math.abs(across) < 1e-6, "on the fissure line, off by " + across);
        assertTrue(Math.abs(along) <= fissure.fissureLengthM() / 2, "within the fissure");
        assertTrue(w.coupler().allVents().stream().anyMatch(v -> v.id().equals(vent.id())), "the vent is a vent of the volcano");
        if (w.volcano().chamber().erupting()) {
            assertTrue(w.coupler().activeVents().stream().anyMatch(v -> v.id().equals(vent.id())), "and erupts");
            w.engine().step();
            assertTrue(w.coupler().ventFluxM3PerS(vent.id()) > 0, "magma leaves through the vent");
        }

        // It survives a save and restore.
        InMemorySaveStore saved = Saves.save(w.engine());
        World restored = world(3, flank(), saved, 1);
        assertTrue(restored.coupler().allVents().stream().anyMatch(v -> v.id().equals(vent.id())));
    }

    @Test
    void sealingAFissureRedirectsToTheSummitAndSealingBothEndsTheEruption() {
        World w = world(3, flankWithSummitConduit(), null, 1);
        List<EngineFrame> frames = new ArrayList<>();
        String fissure = flankEruption(w, frames);

        // Plugging the fissure: the pressurised magma takes the open summit instead.
        w.engine().submit(new VentCommands.SealVent("test", fissure));
        frames.clear();
        frames.addAll(run(w.engine(), MINUTE));
        assertTrue(events(frames, EruptionEnded.class).isEmpty(), "the eruption moves, it does not stop");
        assertEquals(VentStatus.SEALED, w.coupler().ventStatus(fissure));
        assertEquals(List.of(CRATER.id()), w.coupler().activeVents().stream().map(VentSite::id).toList());
        assertEquals(0, w.coupler().ventFluxM3PerS(fissure));
        assertTrue(w.coupler().ventFluxM3PerS(CRATER.id()) > 0);

        // Plugging the summit too leaves no outlet: the eruption ends with the pressure kept.
        w.engine().submit(new VentCommands.SealVent("test", CRATER.id()));
        frames.clear();
        frames.addAll(run(w.engine(), MINUTE));
        List<EruptionEnded> ends = events(frames, EruptionEnded.class);
        assertEquals(1, ends.size());
        assertEquals(MagmaEvents.Cause.SEALED, ends.get(0).cause());
        assertTrue(w.volcano().chamber().overpressureMPa() > 0,
                "a plugged volcano stays pressurised");
        assertTrue(w.volcano().chamber().summitBlocked(), "its conduit is still molten, but blocked at the surface");
        frames.clear();
        frames.addAll(run(w.engine(), 10 * MINUTE));
        assertTrue(events(frames, EruptionStarted.class).isEmpty(), "and cannot fail while sealed");
    }

    @Test
    void sealedSummitCannotFailButAForcedEruptionSendsADike() {
        MagmaChamberConfig atFailure = VolcanoSystemTest.basalt(); // fails through the summit within minutes
        World w = world(1, atFailure, null, 1);
        w.engine().submit(new VentCommands.SealVent("test", CRATER.id()));
        w.engine().submit(new DikeCommands.BlockDikes("test", true));
        List<EngineFrame> frames = run(w.engine(), 10 * MINUTE);
        assertTrue(events(frames, EruptionStarted.class).isEmpty(), "a sealed summit does not fail");
        assertTrue(w.volcano().chamber().overpressureMPa() > atFailure.tensileStrengthMPa(), "pressure keeps building");
        assertTrue(events(frames, DikeEvents.DikeStarted.class).isEmpty(), "blocked dikes do not nucleate");
        assertEquals(VentStatus.SEALED, w.coupler().ventStatus(CRATER.id()));

        // Forcing the eruption: the sealed summit leaves only a dike as the way up; it opens a flank fissure
        // and the eruption goes there.
        w.engine().submit(new MagmaCommands.StartEruption("test"));
        frames = VolcanoSystemTest.runPastOnset(w.engine(), 30 * MINUTE, 60);
        assertFalse(events(frames, DikeEvents.DikeStarted.class).isEmpty(), "the forced eruption starts a dike");
        List<FissureOpened> opened = events(frames, FissureOpened.class);
        assertFalse(opened.isEmpty());
        List<EruptionStarted> starts = events(frames, EruptionStarted.class);
        assertFalse(starts.isEmpty());
        assertEquals(MagmaEvents.Cause.DIKE, starts.get(0).cause());

        // Unsealing reopens the summit.
        w.engine().submit(new VentCommands.UnsealVent("test", CRATER.id()));
        run(w.engine(), 1);
        assertFalse(w.coupler().sealed(CRATER.id()));
    }

    @Test
    void removingAFissureDropsTheVentButKeepsTheIntrusion() {
        World w = world(3, flankWithSummitConduit(), null, 1);
        List<EngineFrame> frames = new ArrayList<>();
        String fissure = flankEruption(w, frames);
        int dikeId = events(frames, FissureOpened.class).get(0).dikeId();
        int geometries = w.volcano().dikes().geometries().size();

        w.engine().submit(new VentCommands.RemoveVent("test", fissure));
        frames.clear();
        frames.addAll(run(w.engine(), MINUTE));
        assertTrue(w.coupler().allVents().stream().noneMatch(v -> v.id().equals(fissure)));
        assertTrue(states(frames, fissure).stream().anyMatch(e -> e.current() == VentStatus.REMOVED));
        Dike dike = w.volcano().dikes().dikes().stream().filter(d -> d.id() == dikeId).findFirst().orElse(null);
        assertNotNull(dike, "the dike stays recorded as an intrusion");
        assertTrue(dike.removed());
        assertEquals(geometries, w.volcano().dikes().geometries().size(), "and still deforms the ground");
        // It was the only outlet: the eruption carries on through the summit.
        assertEquals(List.of(CRATER.id()), w.coupler().activeVents().stream().map(VentSite::id).toList());
    }

    @Test
    void arrestedDikeStallsAndNeverOpensAFissure() {
        World w = world(3, flank(), null, 1);
        w.engine().submit(new DikeCommands.BlockDikes("test", true));
        w.engine().step();
        w.engine().submit(new DikeCommands.ForceDike("test"));
        for (double end = w.engine().time() + MINUTE; w.engine().time() < end && w.volcano().dikes().dikes().isEmpty(); ) {
            w.engine().step();
        }
        Dike dike = w.volcano().dikes().dikes().get(0);
        assertTrue(dike.propagating());
        w.engine().submit(new DikeCommands.ArrestDike("test", dike.id()));
        List<EngineFrame> frames = run(w.engine(), 30 * MINUTE);
        List<DikeEvents.DikeStalled> stalls = events(frames, DikeEvents.DikeStalled.class);
        assertEquals(DikeEvents.StallReason.ARRESTED, stalls.get(0).reason());
        assertTrue(events(frames, FissureOpened.class).stream().noneMatch(f -> f.dikeId() == dike.id()));
    }

    @Test
    void flankEruptionIsDeterministicThreadInvariantAndRestoresBitForBit() {
        World reference = world(3, flank(), null, 1);
        List<EngineFrame> referenceFrames = new ArrayList<>();
        flankEruption(reference, referenceFrames);
        // Save a little into the flank eruption. Short window after it: on this cone, lava entering
        // ponded water diverges a few minutes after a restore (a lava ocean-entry restore issue,
        // independent of the fissure state).
        double save = reference.engine().time();
        double end = save + 4 * 60;
        referenceFrames.addAll(VolcanoSystemTest.until(reference.engine(), end));

        World threaded = world(3, flank(), null, 4);
        List<EngineFrame> threadedFrames = new ArrayList<>();
        flankEruption(threaded, threadedFrames);
        threadedFrames.addAll(VolcanoSystemTest.until(threaded.engine(), end));
        assertEquals(referenceFrames, threadedFrames, "thread count must not matter");

        World first = world(3, flank(), null, 1);
        first.engine().submit(new DikeCommands.BlockDikes("test", true)); // as flankEruption does
        first.engine().step();
        first.engine().submit(new DikeCommands.ForceDike("test"));
        VolcanoSystemTest.until(first.engine(), save);
        assertTrue(first.volcano().chamber().erupting(), "save point should be mid flank eruption");
        InMemorySaveStore saved = Saves.save(first.engine());
        // The restored engine holds its own terrain (persisted world model); nothing is re-sent.
        World second = world(3, flank(), saved, 1);
        long saveMicros = first.engine().timeMicros();
        assertEquals(referenceFrames.stream().filter(f -> f.timeMicros() >= saveMicros).toList(),
                VolcanoSystemTest.until(second.engine(), end));
    }
}
