package me.alex4386.typhon.server;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.massflow.MassFlowField;
import me.alex4386.typhon.engine.subsurface.Subsurface;
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
    /**
     * Fields this server produces. WaterTableDepth, SteamFraction and the subsurface part of
     * SurfaceTemperature come from the shared subsurface model; a scenario without one reports
     * WaterTableDepth as NaN and SteamFraction as 0.
     */
    static final Set<Field> AVAILABLE = Set.of(Field.values());

    static final double AMBIENT_C = 15;
    /** Depth (m) of the "top subsurface cell" sampled for SurfaceTemperature. */
    static final double SURFACE_SAMPLE_DEPTH_M = 1;
    /** Steam is reported as the maximum over these depths (m): the shallow, fumarole-feeding zone. */
    static final double[] STEAM_DEPTHS_M = {2, 10, 30, 80};

    /** The subsurface model shared by the scenario's volcanoes, or {@code null}. */
    static Subsurface subsurface(Scenario scenario) {
        for (VolcanoSystem v : scenario.volcanoes()) {
            if (v.subsurface() != null) return v.subsurface();
        }
        return null;
    }

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
        Subsurface sub = subsurface(scenario);
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
                    v[r * t + c] = (float) value(field, world, lava, noLava, volcanoes, sub, cx, cz);
                }
            }
            tiles[index] = v;
        });
        return tiles;
    }

    private static double value(Field field, WorldModel world, LavaFlow lava, boolean noLava,
            List<VolcanoSystem> volcanoes, Subsurface sub, int x, int z) {
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
                double standing = Double.isFinite(w) && Double.isFinite(s) ? Math.max(0, w - (s + world.uplift(x, z))) : 0;
                // Flowing/poured water and lakes from the surface-water model.
                double flowing = sub != null ? sub.surfaceWaterDepthM(x, z) : 0;
                return Math.max(standing, Double.isFinite(flowing) ? flowing : 0);
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
                if (sub != null && sub.known(x, z)) tC = sub.temperatureC(x, z, SURFACE_SAMPLE_DEPTH_M);
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
            case WATER_TABLE_DEPTH -> {
                return sub != null && sub.known(x, z) ? sub.waterTableDepthM(x, z) : Double.NaN;
            }
            case STEAM_FRACTION -> {
                if (sub == null || !sub.known(x, z)) return 0;
                double steam = 0;
                for (double d : STEAM_DEPTHS_M) steam = Math.max(steam, sub.steamFraction(x, z, d));
                return steam;
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
