package me.alex4386.typhon.server;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.massflow.MassFlowField;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.server.protocol.Field;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * Reads tile fields out of a scenario. Must run on the engine thread (or on a scenario nobody is
 * stepping): it reads live simulation state. Returns one {@code T×T} array per tile, tiles in
 * row-major order ({@code ty·tilesX + tx}), cells row-major from the south-west.
 */
final class FieldSampler {
    /** Fields this server can produce today. WaterTableDepth and SteamFraction need the subsurface model (M4). */
    static final Set<Field> AVAILABLE = Set.of(Field.SURFACE_ELEVATION, Field.LAVA_DEPTH, Field.LAVA_TEMPERATURE,
            Field.WATER_DEPTH, Field.PDC_DEPTH, Field.LAHAR_DEPTH, Field.ASH_DEPTH, Field.SURFACE_TEMPERATURE,
            Field.TOP_UNIT, Field.UPLIFT);

    static final double AMBIENT_C = 15;

    private FieldSampler() {}

    static Map<Field, float[][]> sample(Scenario scenario, GridMapping map, Set<Field> fields) {
        Map<Field, float[][]> out = new EnumMap<>(Field.class);
        for (Field f : fields) {
            if (AVAILABLE.contains(f)) out.put(f, sample(scenario, map, f));
        }
        return out;
    }

    static float[][] sample(Scenario scenario, GridMapping map, Field field) {
        WorldModel world = scenario.terrain().world();
        LavaFlow lava = scenario.lava();
        List<VolcanoSystem> volcanoes = scenario.volcanoes();
        int t = map.tileSize;
        float[][] tiles = new float[map.tilesX * map.tilesY][];
        boolean noLava = lava.activeCellCount() == 0;
        // Runs between engine steps (nothing mutates the world meanwhile): tiles are sampled in
        // parallel, each into its own array.
        scenario.engine().parallel().forEach(tiles.length, index -> {
            int tx = index % map.tilesX;
            int ty = index / map.tilesX;
            float[] v = new float[t * t];
            for (int r = 0; r < t; r++) {
                for (int c = 0; c < t; c++) {
                    int cx = Math.min(map.maxX, map.columnX(tx, c));
                    int cz = Math.max(map.minZ, map.columnZ(ty, r));
                    v[r * t + c] = (float) value(field, world, lava, noLava, volcanoes, cx, cz);
                }
            }
            tiles[index] = v;
        });
        return tiles;
    }

    private static double value(Field field, WorldModel world, LavaFlow lava, boolean noLava,
            List<VolcanoSystem> volcanoes, int x, int z) {
        switch (field) {
            case SURFACE_ELEVATION -> {
                double s = world.surfaceZ(x, z);
                return Double.isFinite(s) ? s + world.uplift(x, z) : world.spec().datumZ();
            }
            case LAVA_DEPTH -> {
                return noLava ? 0 : lava.thickness(x, z);
            }
            case LAVA_TEMPERATURE -> {
                return noLava || lava.thickness(x, z) <= 0 ? 0 : lava.temperatureC(x, z);
            }
            case WATER_DEPTH -> {
                double w = world.waterZ(x, z);
                double s = world.surfaceZ(x, z);
                return Double.isFinite(w) && Double.isFinite(s) ? Math.max(0, w - (s + world.uplift(x, z))) : 0;
            }
            case PDC_DEPTH -> {
                double d = 0;
                for (VolcanoSystem v : volcanoes) d += depth(v.pyroclasticFlows(), x, z);
                return d;
            }
            case LAHAR_DEPTH -> {
                double d = 0;
                for (VolcanoSystem v : volcanoes) d += depth(v.lahars(), x, z);
                return d;
            }
            case ASH_DEPTH -> {
                double d = 0;
                for (VolcanoSystem v : volcanoes) d += v.tephra().depositThickness(x, z);
                return d;
            }
            case SURFACE_TEMPERATURE -> {
                double tC = AMBIENT_C;
                for (VolcanoSystem v : volcanoes) {
                    Geothermal g = v.geothermal();
                    if (g != null) tC = Math.max(tC, g.temperatureAt(x, z));
                }
                if (!noLava && lava.thickness(x, z) > 0) tC = Math.max(tC, lava.temperatureC(x, z));
                return tC;
            }
            case TOP_UNIT -> {
                int n = world.layerCount(x, z);
                if (n <= 0) return 0;
                LayerView top = world.layer(x, z, n - 1);
                return top.unit() < 0 ? 0 : top.unit() + 1;
            }
            case UPLIFT -> {
                return world.uplift(x, z);
            }
            default -> {
                return 0;
            }
        }
    }

    private static double depth(MassFlowField f, int x, int z) {
        return f == null || f.activeCellCount() == 0 ? 0 : f.depth(x, z);
    }
}
