package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.alert.AlertLevel;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLaunched;
import me.alex4386.typhon.engine.tephra.TephraEvents.PlumeColumn;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

/** End-to-end: chamber → seismicity → alert → coupler → lava / tephra / geothermal on a cone. */
class VolcanoSystemTest {
    private static final BlockId STONE = BlockId.minecraft("stone");
    private static final BlockId LAVA = BlockId.minecraft("lava");
    private static final int CHUNK_RADIUS = 8;
    private static final int SUMMIT_Y = 120;
    private static final VentSite CRATER = VentSite.crater("summit", new BlockPos(0, SUMMIT_Y - 3, 0), 4);

    /** Cone rising from y=64 to a summit crater, sampled the way a host would send it. */
    static TerrainSnapshot cone() {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -CHUNK_RADIUS; cx < CHUNK_RADIUS; cx++) {
            for (int cz = -CHUNK_RADIUS; cz < CHUNK_RADIUS; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        double d = Math.sqrt(x * x + z * z);
                        int y = (int) Math.max(64, SUMMIT_Y - 0.45 * d);
                        if (d <= CRATER.craterRadius()) y = CRATER.position().y();
                        chunk.set(x, z, TerrainColumn.dry(y, STONE));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    /** Re-samples a terrain model over the test area (what a host does after a restart). */
    static TerrainSnapshot resample(TerrainModel terrain) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -CHUNK_RADIUS; cx < CHUNK_RADIUS; cx++) {
            for (int cz = -CHUNK_RADIUS; cz < CHUNK_RADIUS; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        chunk.set(x, z, terrain.column(x, z));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    record World(Engine engine, TerrainModel terrain, LavaFlow lava, VolcanoSystem volcano) {}

    static World world(long seed, MagmaChamberConfig chamber, JsonObject restore) {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder("test", List.of(CRATER), terrain, lava)
                .chamber(chamber)
                .scaling(VolcanoScaling.DEFAULT)
                .build();
        Engine.Builder builder = Engine.builder(seed).add(terrain);
        volcano.addTo(builder).add(lava);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        engine.submit(cone());
        return new World(engine, terrain, lava, volcano);
    }

    /** Basaltic chamber just below failure: erupts within minutes. */
    static MagmaChamberConfig basalt() {
        return MagmaChamberConfig.builder("test", new BlockPos(0, 60, 0))
                .initialOverpressureMPa(14.5)
                .supplyVariability(0)
                .build();
    }

    /**
     * Wet rhyolite already at failure. (Gas-rich magma is compressible, so a rhyolite chamber below
     * failure pressurises far more slowly than a basaltic one.)
     */
    static MagmaChamberConfig rhyolite() {
        return MagmaChamberConfig.builder("test", new BlockPos(0, 60, 0))
                .initialOverpressureMPa(15.5)
                .initialSilicaWt(72)
                .initialWaterWt(6)
                .initialTemperatureC(850)
                .rechargeSilicaWt(72)
                .rechargeWaterWt(6)
                .rechargeTemperatureC(850)
                .supplyVariability(0)
                .build();
    }

    static List<EngineFrame> run(Engine engine, int ticks) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.tick());
        return frames;
    }

    static <T extends EngineEvent> List<T> events(List<EngineFrame> frames, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EngineFrame f : frames) for (EngineEvent e : f.events()) if (type.isInstance(e)) out.add(type.cast(e));
        return out;
    }

    @Test
    void basalticEruptionPoursLavaAndShakes() {
        World w = world(1, basalt(), null);
        List<EngineFrame> frames = run(w.engine(), 20 * 60 * 5);

        assertFalse(events(frames, EruptionStarted.class).isEmpty(), "chamber should fail and erupt");
        assertTrue(w.volcano().chamber().erupting());
        assertEquals(AlertLevel.ERUPTING, w.volcano().alert().level());
        assertTrue(w.volcano().coupler().effusing(), "basalt should effuse");
        assertFalse(w.volcano().chamber().fragmented());
        assertFalse(events(frames, SeismicEvent.class).isEmpty(), "unrest and eruption should be seismic");

        boolean lavaPlaced = frames.stream().flatMap(f -> f.blockChanges().stream())
                .map(BlockChange::to).anyMatch(s -> s.id().equals(LAVA));
        assertTrue(lavaPlaced, "lava blocks should appear at the surface");
        assertTrue(w.lava().totalLavaVolume() + w.lava().solidifiedVolume() > 0);
        // Volume reaching the world is the real erupted volume scaled by 1/L³.
        double expected = w.volcano().chamber().eruptedVolume() * VolcanoScaling.DEFAULT.volumeScale();
        assertEquals(expected, w.lava().emittedVolume(), expected * 0.2 + 1);
    }

    @Test
    void wetRhyoliteEruptsExplosively() {
        World w = world(2, rhyolite(), null);
        List<EngineFrame> frames = run(w.engine(), 20 * 30);

        assertFalse(events(frames, EruptionStarted.class).isEmpty());
        assertTrue(w.volcano().chamber().erupting());
        assertTrue(w.volcano().chamber().fragmented(), "water-rich magma should fragment");
        assertTrue(w.volcano().coupler().explosive());
        assertFalse(w.volcano().coupler().effusing(), "fragmented magma should not pour lava");
        assertFalse(events(frames, PlumeColumn.class).isEmpty(), "an eruption column should form");
        assertFalse(events(frames, BombLaunched.class).isEmpty(), "ballistics should fly");
        assertEquals(0, w.lava().emittedVolume(), "no lava from a fragmented eruption");
    }

    @Test
    void sameSeedSameEruption() {
        List<EngineFrame> a = run(world(5, basalt(), null).engine(), 20 * 90);
        List<EngineFrame> b = run(world(5, basalt(), null).engine(), 20 * 90);
        assertEquals(a, b);
    }

    @Test
    void saveAndRestoreMidEruptionIsBitForBit() {
        int before = 20 * 120;
        int after = 20 * 60;

        World reference = world(9, basalt(), null);
        List<EngineFrame> referenceFrames = run(reference.engine(), before + after);

        World first = world(9, basalt(), null);
        run(first.engine(), before);
        assertTrue(first.volcano().chamber().erupting(), "save point should be mid-eruption");
        String saved = first.engine().saveState().toString();
        TerrainSnapshot terrain = resample(first.terrain());

        World second = world(9, basalt(), JsonParser.parseString(saved).getAsJsonObject());
        second.engine().submit(terrain); // host re-sends the live terrain after the cone it submitted at boot
        List<EngineFrame> resumed = run(second.engine(), after);

        assertEquals(referenceFrames.subList(before, before + after), resumed);
    }
}
