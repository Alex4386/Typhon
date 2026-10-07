package me.alex4386.typhon.engine.save;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.testing.Runs;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveFormatTest {
    private static final VentSite VENT = VentSite.crater("summit", Point3.surfaceOf(new BlockPos(0, 97, 0), VolcanoScaling.DEFAULT.metersPerBlock()), 3);

    record World(Engine engine, TerrainModel terrain, LavaFlow lava, VolcanoSystem volcano) {}

    /** A small cone with an erupting basaltic chamber. */
    static World world(SaveStore restore) {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder("v", List.of(VENT), terrain, lava)
                .chamber(MagmaChamberConfig.builder("v", Point3.ofBlock(new BlockPos(0, 40, 0), VolcanoScaling.DEFAULT.metersPerBlock()))
                        .initialOverpressureMPa(14.9999).supplyVariability(0).build())
                .scaling(VolcanoScaling.DEFAULT)
                .dikesEnabled(false)
                .build();
        Engine.Builder builder = Engine.builder(21).adaptive(Engine.DEFAULT_MAX_STEP_SECONDS).add(terrain);
        volcano.addTo(builder).add(lava);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        if (restore == null) engine.submit(cone());
        return new World(engine, terrain, lava, volcano);
    }

    static TerrainSnapshot cone() {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -4; cx < 4; cx++) {
            for (int cz = -4; cz < 4; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        double d = Math.sqrt(x * x + z * z);
                        int y = (int) Math.max(64, 100 - 0.5 * d);
                        if (d <= VENT.craterRadius()) y = VENT.block(VolcanoScaling.DEFAULT.metersPerBlock()).y();
                        chunk.set(x, z, TerrainColumn.dry(y, BlockId.minecraft("stone")));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    @Test
    void directoryRoundTripResumesBitForBit(@TempDir Path dir) {
        World reference = world(null);
        List<EngineFrame> expected = Runs.runPastOnset(reference.engine(), 86_400, 1200);
        double save = Runs.onset(expected) + 600;
        double end = reference.engine().time();

        World first = world(null);
        Runs.until(first.engine(), save);
        assertTrue(first.volcano().chamber().erupting());
        DirectorySaveStore store = new DirectorySaveStore(dir);
        first.engine().save(store);

        // Layout: meta, one JSON per subsystem, region files for spatial fields, history log.
        assertTrue(Files.isRegularFile(dir.resolve("meta.json")));
        assertTrue(Files.isRegularFile(dir.resolve("subsystems/magma%3Av.json")));
        assertFalse(store.list("fields/terrain/columns/").isEmpty(), "terrain is persisted");
        assertFalse(store.list("fields/lava/cells/").isEmpty(), "lava cells are region files");
        assertTrue(store.list("fields/").stream().allMatch(p -> p.matches(".*/r\\.-?\\d+\\.-?\\d+\\.bin")));
        String history = new String(store.read(SaveFormat.HISTORY), StandardCharsets.UTF_8);
        assertTrue(history.contains("\"type\":\"EruptionStarted\""), history);

        // The restored engine needs no terrain from the host: the save is self-contained.
        World second = world(new DirectorySaveStore(dir));
        assertEquals(first.engine().stateHash(), second.engine().stateHash());
        List<EngineFrame> resumed = Runs.until(second.engine(), end);
        long saveMicros = first.engine().timeMicros();
        assertEquals(expected.stream().filter(f -> f.timeMicros() >= saveMicros).toList(), resumed);
    }

    @Test
    void incrementalSaveRewritesOnlyChangedFiles(@TempDir Path dir) {
        World w = world(null);
        Runs.runPastOnset(w.engine(), 86_400, 60);
        DirectorySaveStore store = new DirectorySaveStore(dir);
        w.engine().save(store);
        long first = store.writeCount();
        assertTrue(first > 5);

        // Nothing changed: nothing is rewritten.
        w.engine().save(store);
        assertEquals(first, store.writeCount());

        // A short step changes the lava near the vent and the chamber, but not every terrain region.
        w.engine().step();
        w.engine().save(store);
        long rewritten = store.writeCount() - first;
        int regions = store.list("fields/").size();
        assertTrue(rewritten > 0);
        assertTrue(rewritten < store.list("").size(), "rewrote " + rewritten + " of " + store.list("").size()
                + " files (" + regions + " regions)");
    }

    @Test
    void historyIsAppendedOncePerEvent(@TempDir Path dir) {
        World w = world(null);
        DirectorySaveStore store = new DirectorySaveStore(dir);
        Runs.runPastOnset(w.engine(), 86_400, 600);
        w.engine().save(store);
        w.engine().save(store);
        String history = new String(store.read(SaveFormat.HISTORY), StandardCharsets.UTF_8);
        long starts = history.lines().filter(l -> l.contains("\"type\":\"EruptionStarted\"")).count();
        assertEquals(1, starts);
        assertTrue(w.engine().unsavedHistory().isEmpty());
    }

    @Test
    void regionFilesRoundTripTypedArraysExactly() {
        InMemorySaveStore store = new InMemorySaveStore();
        SubsystemState state = new SubsystemState();
        state.json().addProperty("answer", 42);
        double[] d = {0.1, -0.0, Double.NaN, Double.MIN_VALUE, 1e300};
        state.field("f", 3).put(-33, 70, new FieldChunk()
                .doubles("d", d).floats("f", new float[] {1.5f}).ints("i", new int[] {-1, 7})
                .longs("l", new long[] {Long.MIN_VALUE}).bytes("b", new byte[] {-2}).booleans("z", new boolean[] {true, false}));
        state.field("f", 3).put(0, 0, new FieldChunk().ints("i", new int[] {9}));
        SaveFormat.writeSubsystem(store, "x:y", 123L, state);

        assertTrue(store.read("subsystems/x%3Ay.json") != null);
        assertEquals(2, store.list("fields/x%3Ay/f/").size(), "two chunks in different regions");

        SaveFormat.LoadedSubsystem loaded = SaveFormat.readSubsystem(store, "x:y");
        assertNotNull(loaded);
        assertEquals(123L, loaded.randomState());
        assertEquals(42, loaded.state().json().get("answer").getAsInt());
        StateReader.Field f = loaded.state().field("f");
        assertEquals(3, f.schemaVersion());
        FieldChunk c = f.get(-33, 70);
        double[] back = c.doubles("d");
        for (int i = 0; i < d.length; i++) assertEquals(Double.doubleToRawLongBits(d[i]), Double.doubleToRawLongBits(back[i]));
        assertArrayEquals(new int[] {-1, 7}, c.ints("i"));
        assertArrayEquals(new long[] {Long.MIN_VALUE}, c.longs("l"));
        assertArrayEquals(new boolean[] {true, false}, c.booleans("z"));
        assertArrayEquals(new int[] {9}, f.get(0, 0).ints("i"));
    }

    @Test
    void removedFieldChunksDeleteTheirRegionFiles() {
        InMemorySaveStore store = new InMemorySaveStore();
        SubsystemState a = new SubsystemState();
        a.field("f", 1).put(0, 0, new FieldChunk().ints("i", new int[] {1}));
        a.field("f", 1).put(100, 0, new FieldChunk().ints("i", new int[] {2}));
        SaveFormat.writeSubsystem(store, "s", 0, a);
        assertEquals(2, store.list("fields/s/").size());

        SubsystemState b = new SubsystemState();
        b.field("f", 1).put(0, 0, new FieldChunk().ints("i", new int[] {1}));
        SaveFormat.writeSubsystem(store, "s", 0, b);
        assertEquals(List.of("fields/s/f/r.0.0.bin"), store.list("fields/s/"));
    }

    @Test
    void saveLayoutCanBeRootedInAnyDirectory(@TempDir Path dir) {
        // Worlds will keep each engine's state under worlds/<world>/state/; the layout is relative.
        Subsystem counter = new Subsystem() {
            int n;
            @Override public String id() { return "counter"; }
            @Override public void step(StepContext context) { n++; }
            @Override public void saveState(StateWriter out) { out.json().addProperty("n", n); }
            @Override public void loadState(StateReader in) { n = in.json().get("n").getAsInt(); }
        };
        Engine engine = Engine.builder(1).add(counter).build();
        for (int i = 0; i < 5; i++) engine.step();
        engine.save(new DirectorySaveStore(dir.resolve("worlds/demo/state")));
        assertTrue(Files.isRegularFile(dir.resolve("worlds/demo/state/meta.json")));
        assertTrue(Files.isRegularFile(dir.resolve("worlds/demo/state/subsystems/counter.json")));
    }
}
