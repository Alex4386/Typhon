package me.alex4386.typhon.engine.dike;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.GroundImport;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.testing.TestGround.Elevation;
import me.alex4386.typhon.engine.save.SaveStore;

/** Shared fixtures: terrains (10 m columns, 3.84 km square), chambers and an engine wired with chamber → dike. */
final class DikeTestWorld {
    static final double COLUMN_M = 10;
    static final int CHUNK_RADIUS = 12;

    private DikeTestWorld() {}

    /** Dry ground over the test square; {@code height} gives the surface elevation (m) per column. */
    static GroundImport terrain(Elevation height) {
        return TestGround.chunks(-CHUNK_RADIUS, -CHUNK_RADIUS, CHUNK_RADIUS - 1, CHUNK_RADIUS - 1, height,
                TestGround.DRY);
    }

    static GroundImport flat() {
        return terrain((x, z) -> 64);
    }

    /** Cone rising from 64 m to 1264 m at the axis, slope 0.6 (radius 2 km). */
    static GroundImport cone() {
        return terrain((x, z) -> Math.max(64, 1264 - 0.6 * COLUMN_M * Math.hypot(x + 0.5, z + 0.5)));
    }

    /** Hot, dry basaltic chamber (no exsolved gas, so its compressibility is constant). */
    static MagmaChamberConfig.Builder basalt(double overpressure) {
        return MagmaChamberConfig.builder("v", new Point3(0, -4000, 0))
                .volume(1e11)
                .initialOverpressureMPa(overpressure)
                .initialTemperatureC(1180)
                .initialSilicaWt(50)
                .initialWaterWt(0.5)
                .supplyRate(0)
                .supplyVariability(0);
    }

    /** Engine base step of these tests (1 s). */
    static final long TICK_MICROS = 1_000_000;

    static DikeConfig fastConfig() {
        DikeConfig c = DikeConfig.defaults();
        return c;
    }

    record World(Engine engine, MagmaChamber chamber, DikePropagation dikes, TerrainModel terrain) {}

    static World world(long seed, MagmaChamberConfig chamberConfig, DikeConfig config, GroundImport ground,
            SaveStore restore) {
        TerrainModel terrain = TestGround.terrain(COLUMN_M);
        MagmaChamber chamber = new MagmaChamber(chamberConfig);
        DikePropagation dikes = new DikePropagation(config, DikeMagmaSource.of(chamber), terrain);
        // one-second ticks: dikes rise metres per second, so tests run minutes of propagation quickly
        Engine.Builder builder = Engine.builder(seed).baseStepMicros(TICK_MICROS).add(terrain).add(chamber).add(dikes);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        engine.submit(ground);
        return new World(engine, chamber, dikes, terrain);
    }

    static List<EngineFrame> run(Engine engine, int ticks) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.step());
        return frames;
    }

    /** Runs until the first event of {@code type} (or {@code maxSteps}); returns its step or −1. */
    static long runUntil(Engine engine, Class<? extends EngineEvent> type, int maxSteps) {
        for (int i = 0; i < maxSteps; i++) {
            EngineFrame frame = engine.step();
            for (EngineEvent e : frame.events()) if (type.isInstance(e)) return frame.step();
        }
        return -1;
    }

    static <T extends EngineEvent> List<T> events(List<EngineFrame> frames, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EngineFrame f : frames) for (EngineEvent e : f.events()) if (type.isInstance(e)) out.add(type.cast(e));
        return out;
    }
}
