package me.alex4386.typhon.engine.lava;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.testing.TestGround.Elevation;

/** Synthetic terrain (1 m columns, elevations in metres) plus helpers for driving a {@link LavaFlow} in tests. */
final class LavaTestWorld {
    static final double NO_WATER = Double.NaN;
    static final Elevation NO_WATER_EVERYWHERE = TestGround.DRY;
    static final double COLUMN_M = 1.0;

    final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    final double metersPerColumn;
    final TerrainModel terrain;
    final List<EngineFrame> frames = new ArrayList<>();

    /**
     * Terrain of {@code metersPerColumn}-wide columns covering 16-column chunks [minChunk, maxChunk] inclusive;
     * surface and water elevations in metres.
     */
    LavaTestWorld(double metersPerColumn, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            Elevation ground, Elevation water) {
        this.metersPerColumn = metersPerColumn;
        this.terrain = TestGround.terrain(metersPerColumn);
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
        terrain.apply(TestGround.chunks(minChunkX, minChunkZ, maxChunkX, maxChunkZ, ground, water));
    }

    /** 1 m columns. */
    LavaTestWorld(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Elevation ground, Elevation water) {
        this(COLUMN_M, minChunkX, minChunkZ, maxChunkX, maxChunkZ, ground, water);
    }

    LavaTestWorld(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Elevation ground) {
        this(minChunkX, minChunkZ, maxChunkX, maxChunkZ, ground, TestGround.DRY);
    }

    /** Copy of the current terrain (including engine edits), as a host would re-send after a restart. */
    TerrainModel copyTerrain() {
        TerrainModel copy = TestGround.terrain(metersPerColumn);
        copy.apply(TestGround.copy(terrain.world(), minChunkX * 16, minChunkZ * 16, maxChunkX * 16 + 15,
                maxChunkZ * 16 + 15));
        return copy;
    }

    /** Base step of engines this world builds (µs); {@link #coarse} lengthens it. */
    long baseStepMicros = Engine.DEFAULT_BASE_STEP_MICROS;

    /** Engines built from now on step {@code factor} × 50 ms per tick (lava sub-steps inside). */
    LavaTestWorld coarse(double factor) {
        baseStepMicros = Math.round(Engine.DEFAULT_BASE_STEP_MICROS * factor);
        return this;
    }

    Engine engine(LavaFlow lava, long seed) {
        return Engine.builder(seed).baseStepMicros(baseStepMicros).add(terrain).add(lava).build();
    }

    Engine engine(LavaFlow lava, long seed, int threads) {
        return Engine.builder(seed).baseStepMicros(baseStepMicros).threads(threads).add(terrain).add(lava).build();
    }

    void run(Engine engine, int ticks) {
        for (int i = 0; i < ticks; i++) frames.add(engine.step());
    }

    <T extends EngineEvent> List<T> events(Class<T> type) {
        List<T> list = new ArrayList<>();
        for (EngineFrame frame : frames) {
            for (EngineEvent e : frame.events()) if (type.isInstance(e)) list.add(type.cast(e));
        }
        return list;
    }

    static double maxX(LavaFlow lava, int minX, int maxX, int minZ, int maxZ) {
        double best = Double.NEGATIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (lava.thickness(x, z) > 0) best = Math.max(best, x);
            }
        }
        return best;
    }

    /** Rheology with fixed viscosity and yield strength, for isolating the flow law. */
    record FixedRheology(double viscosity, double yieldStrength) implements LavaRheology {
        @Override public double viscosityPaS(double temperatureC, double silicaWt, double waterWt) { return viscosity; }
        @Override public double yieldStrengthPa(double temperatureC, double silicaWt) { return yieldStrength; }
        @Override public double liquidusC(double silicaWt) { return 1200; }
        @Override public double solidusC(double silicaWt) { return 1000; }
    }
}
