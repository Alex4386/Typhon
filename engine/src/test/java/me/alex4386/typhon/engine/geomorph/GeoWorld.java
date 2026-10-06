package me.alex4386.typhon.engine.geomorph;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.WorldModel;

/** Synthetic terrain for geomorphology tests: 1 m columns of andesite ("stone"). */
final class GeoWorld {
    static final BlockId STONE = BlockId.minecraft("stone");

    final int size;
    final TerrainModel terrain = new TerrainModel();
    final WorldModel world = terrain.world();
    final List<EngineFrame> frames = new ArrayList<>();

    /** {@code chunks × chunks} terrain chunks from (0, 0) with ground {@code ground(x, z)}. */
    GeoWorld(int chunks, IntBinaryOperator ground) {
        this.size = chunks * 16;
        List<TerrainChunk> list = new ArrayList<>();
        for (int cx = 0; cx < chunks; cx++) {
            for (int cz = 0; cz < chunks; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx * 16 + lx;
                        int z = cz * 16 + lz;
                        chunk.set(x, z, new TerrainColumn(ground.applyAsInt(x, z), TerrainColumn.NO_WATER, STONE));
                    }
                }
                list.add(chunk);
            }
        }
        terrain.apply(new TerrainSnapshot(list));
    }

    static GeoWorld flat(int chunks, int y) {
        return new GeoWorld(chunks, (x, z) -> y);
    }

    Engine engine(long seed, int threads, Subsystem... subsystems) {
        Engine.Builder b = Engine.builder(seed).baseStepMicros(1_000_000).threads(threads).add(terrain);
        for (Subsystem s : subsystems) b.add(s);
        return b.build();
    }

    void run(Engine engine, int steps) {
        for (int i = 0; i < steps; i++) frames.add(engine.step());
    }

    /** Runs until {@code geo} has nothing queued (or {@code max} steps). */
    int settle(Engine engine, Geomorphology geo, int max) {
        int n = 0;
        do {
            run(engine, 1);
            n++;
        } while (geo.queuedColumns() > 0 && n < max);
        return n;
    }

    <T extends EngineEvent> List<T> events(Class<T> type) {
        List<T> list = new ArrayList<>();
        for (EngineFrame f : frames) for (EngineEvent e : f.events()) if (type.isInstance(e)) list.add(type.cast(e));
        return list;
    }

    /** Solid volume (m³) of {@code material} (or every solid if null) above {@code minZ} over the whole area. */
    double solid(Material material, double minZ) {
        double sum = 0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int k = 0; k < world.layerCount(x, z); k++) {
                    LayerView l = world.layer(x, z, k);
                    if (material != null && l.material() != material.id()) continue;
                    if (!l.materialInfo().solid()) continue;
                    double seg = l.top() - Math.max(l.bottom(), minZ);
                    if (seg <= 0) continue;
                    sum += seg * (1 - l.porosity()) * (1 - l.voidFraction());
                }
            }
        }
        return sum;
    }

    /** Steepest 4-neighbour surface gradient (°) over the interior where {@code mask} holds. */
    double maxSlopeDeg(int x0, int z0, int x1, int z1) {
        double best = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double s = world.surfaceZ(x, z);
                double[] n = {world.surfaceZ(x + 1, z), world.surfaceZ(x - 1, z), world.surfaceZ(x, z + 1),
                        world.surfaceZ(x, z - 1)};
                for (double v : n) if (!Double.isNaN(v)) best = Math.max(best, s - v);
            }
        }
        return Math.toDegrees(Math.atan(best));
    }
}
