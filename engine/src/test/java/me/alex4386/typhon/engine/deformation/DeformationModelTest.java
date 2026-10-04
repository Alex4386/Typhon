package me.alex4386.typhon.engine.deformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.deformation.DeformationEvents.DeformationSample;
import me.alex4386.typhon.engine.deformation.DeformationEvents.GroundDeformed;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.testing.StubMagmaState;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveStore;

class DeformationModelTest {
    private static final BlockId STONE = BlockId.minecraft("stone");
    private static final double NU = 0.25;

    // ── Analytic sources ──

    @Test
    void mogiMatchesAnalyticSolution() {
        double dv = 1e6;
        double d = 3000;
        Displacement above = Mogi.displacement(dv, d, 0, 0, NU);
        assertEquals((1 - NU) * dv / (Math.PI * d * d), above.up(), 1e-15);
        assertEquals(0, above.horizontal(), 1e-15);

        Displacement atDepthDistance = Mogi.displacement(dv, d, d, 0, NU);
        double expected = (1 - NU) * dv / Math.PI * d / Math.pow(2 * d * d, 1.5);
        assertEquals(expected, atDepthDistance.up(), 1e-15);
        assertEquals(expected, atDepthDistance.east(), 1e-15, "u_r = u_z at r = d");

        double previous = Double.MAX_VALUE;
        for (double r = 0; r <= 20000; r += 500) {
            double up = Mogi.displacement(dv, d, r, 0, NU).up();
            assertTrue(up < previous, "uplift decreases with distance");
            previous = up;
        }
        Displacement north = Mogi.displacement(dv, d, 0, 1000, NU);
        assertTrue(north.north() > 0 && Math.abs(north.east()) < 1e-15, "radial motion points away from the source");
    }

    @Test
    void mogiVolumeChangeOfASphere() {
        double volume = 4.0 / 3 * Math.PI * 1000 * 1000 * 1000; // a = 1 km
        assertEquals(Math.PI * 1e9 * 10e6 / 3e9, Mogi.volumeChange(volume, 10, 3e9), 1e-3);
        assertTrue(Mogi.volumeChange(volume, -5, 3e9) < 0, "deflation shrinks the cavity");
    }

    @Test
    void dikeOpeningPushesWallsApartWithFlankingUplift() {
        DikeGeometry surfacing = new DikeGeometry(0, 0, 0, 2000, 0, 3000, 2.0); // strike along +X (east)
        Displacement north = DikeDislocation.displacement(surfacing, 0, 5);
        Displacement south = DikeDislocation.displacement(surfacing, 0, -5);
        assertTrue(north.north() > 0.8 * 1.0 && south.north() < -0.8, "walls separate by ≈ the opening");
        assertEquals(north.north(), -south.north(), 1e-12);
        assertEquals(0, north.east(), 1e-12);

        DikeGeometry buried = new DikeGeometry(0, 0, 0, 2000, 400, 3000, 2.0);
        assertEquals(0, DikeDislocation.displacement(buried, 0, 0).up(), 1e-12, "no uplift right above the plane");
        double lobe = DikeDislocation.displacement(buried, 0, Math.sqrt(400 * 3000)).up();
        assertTrue(lobe > 0, "uplift lobes flank the dike");
        assertTrue(DikeDislocation.displacement(buried, 0, 100000).up() < lobe * 0.01, "decays far away");
        assertTrue(DikeDislocation.displacement(buried, 20000, Math.sqrt(400 * 3000)).up() < lobe * 0.01,
                "and beyond the dike's ends");
    }

    // ── Model ──

    private static DeformationConfig config(double metersPerBlock) {
        DeformationConfig c = new DeformationConfig("v", 1e10, 4000, 0, 0);
        c.metersPerBlock = metersPerBlock;
        c.stations = List.of(new GeodeticStation("SUMMIT", 0, 0), new GeodeticStation("EAST", 200, 0));
        return c;
    }

    @Test
    void inflationRaisesAndDeflationLowersTheSummit() {
        StubMagmaState magma = StubMagmaState.basalt();
        DeformationModel model = new DeformationModel(config(4), magma, null, null);

        magma.overpressure = 10;
        assertTrue(model.upliftAt(0, 0) > 0);
        double expected = (1 - NU) * Mogi.volumeChange(1e10, 10, 3e9) / (Math.PI * 4000 * 4000);
        assertEquals(expected, model.upliftAt(0, 0), expected * 1e-3);

        StationReading east = model.read(new GeodeticStation("EAST", 200, 0));
        assertTrue(east.displacement().east() > 0, "east station moves east during inflation");
        assertTrue(east.tiltEastMicroRad() < 0, "ground rises toward the summit, i.e. westward");

        magma.overpressure = -5;
        assertTrue(model.upliftAt(0, 0) < 0);
        assertTrue(model.read(new GeodeticStation("EAST", 200, 0)).displacement().east() < 0);
    }

    @Test
    void samplesStationsPeriodically() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressure = 8;
        DeformationModel model = new DeformationModel(config(4), magma, null, null);
        Engine engine = Engine.builder(0).add(model).build();
        List<DeformationSample> samples = events(run(engine, 1000), DeformationSample.class);
        assertEquals(5, samples.size());
        DeformationSample s = samples.get(0);
        assertEquals(List.of("SUMMIT", "EAST"), s.stations().stream().map(StationReading::name).toList());
        assertEquals(model.upliftAt(0, 0), s.summitUpliftM(), 1e-15);
        assertTrue(s.stations().get(0).displacement().up() > s.stations().get(1).displacement().up());
    }

    @Test
    void dikeSourcesAddToTheField() {
        StubMagmaState magma = StubMagmaState.basalt();
        List<DikeGeometry> dikes = new ArrayList<>();
        DeformationModel model = new DeformationModel(config(4), magma, () -> dikes, null);
        double without = model.displacementAt(50.5, 10.5).north();
        dikes.add(new DikeGeometry(50, 0, 0, 1000, 0, 4000, 2));
        double with = model.displacementAt(50.5, 10.5).north();
        // point lies at z = +10 (south of the dike): it is pushed further south
        assertTrue(with < without - 0.5, "dike opening displaces nearby ground: " + with);
    }

    // ── Terrain ──

    private static TerrainSnapshot flat(int radius) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -(radius >> 4) - 1; cx <= (radius >> 4); cx++) {
            for (int cz = -(radius >> 4) - 1; cz <= (radius >> 4); cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) chunk.set(x, z, TerrainColumn.dry(64, STONE));
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    /** Shallow, strongly pressurised source so the uplift spans several blocks. */
    private static DeformationConfig shallowConfig() {
        DeformationConfig c = new DeformationConfig("v", 1e9, 200, 0, 0);
        c.metersPerBlock = 4;
        c.terrainRadiusBlocks = 6;
        c.terrainPeriodSeconds = 1;
        c.maxTerrainChangesPerCheck = 10_000;
        return c;
    }

    record World(Engine engine, TerrainModel terrain, DeformationModel model) {}

    private static World world(StubMagmaState magma, SaveStore restore, TerrainSnapshot snapshot) {
        TerrainModel terrain = new TerrainModel();
        DeformationModel model = new DeformationModel(shallowConfig(), magma, null, terrain);
        Engine.Builder builder = Engine.builder(0).add(terrain).add(model);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        engine.submit(snapshot);
        return new World(engine, terrain, model);
    }

    @Test
    void wholeBlocksOfUpliftRaiseTheTerrainOneStepAtATime() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressure = 50;
        World w = world(magma, null, flat(32));
        int target = (int) (w.model().upliftAt(0, 0) / 4);
        assertTrue(target >= 3, "test source should lift several blocks: " + target);

        List<EngineFrame> first = run(w.engine(), 20);
        BlockChange change = first.get(0).blockChanges().stream()
                .filter(c -> c.pos().equals(new BlockPos(0, 65, 0))).findFirst().orElseThrow();
        assertEquals(BlockId.AIR, change.expected());
        assertEquals(BlockState.of(STONE), change.to());
        assertEquals(65, w.terrain().column(0, 0).groundY());
        assertEquals(1, w.model().appliedBlocks(0, 0));
        assertFalse(events(first, GroundDeformed.class).isEmpty());

        run(w.engine(), 20 * 20);
        assertEquals(target, w.model().appliedBlocks(0, 0));
        assertEquals(64 + target, w.terrain().column(0, 0).groundY());
        assertNull(w.terrain().column(1000, 1000));

        magma.overpressure = 0;
        List<EngineFrame> relax = run(w.engine(), 20 * 20);
        assertEquals(0, w.model().appliedBlocks(0, 0));
        assertEquals(64, w.terrain().column(0, 0).groundY(), "deflation returns the ground");
        assertTrue(relax.stream().flatMap(f -> f.blockChanges().stream())
                .anyMatch(c -> c.pos().equals(new BlockPos(0, 64 + target, 0)) && c.expected().equals(STONE)
                        && c.to().equals(BlockState.AIR)));
    }

    @Test
    void saveAndRestoreKeepsAppliedDeformation() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressure = 50;
        World reference = world(magma, null, flat(32));
        List<EngineFrame> expected = run(reference.engine(), 400);

        StubMagmaState magma2 = StubMagmaState.basalt();
        magma2.overpressure = 50;
        World first = world(magma2, null, flat(32));
        run(first.engine(), 60);
        InMemorySaveStore saved = Saves.save(first.engine());
        TerrainSnapshot live = resample(first.terrain(), 32);

        StubMagmaState magma3 = StubMagmaState.basalt();
        magma3.overpressure = 50;
        World second = world(magma3, saved, live);
        assertEquals(first.model().appliedBlocks(0, 0), second.model().appliedBlocks(0, 0));
        assertEquals(expected.subList(60, 400), run(second.engine(), 340));
    }

    private static TerrainSnapshot resample(TerrainModel terrain, int radius) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -(radius >> 4) - 1; cx <= (radius >> 4); cx++) {
            for (int cz = -(radius >> 4) - 1; cz <= (radius >> 4); cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) chunk.set(x, z, terrain.column(x, z));
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    // ── helpers ──

    private static List<EngineFrame> run(Engine engine, int ticks) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.step());
        return frames;
    }

    private static <T extends EngineEvent> List<T> events(List<EngineFrame> frames, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EngineFrame f : frames) for (EngineEvent e : f.events()) if (type.isInstance(e)) out.add(type.cast(e));
        return out;
    }
}
