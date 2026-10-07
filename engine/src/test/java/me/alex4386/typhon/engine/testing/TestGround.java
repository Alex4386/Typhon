package me.alex4386.typhon.engine.testing;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.terrain.GroundColumn;
import me.alex4386.typhon.engine.terrain.GroundImport;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;

/** Synthetic ground for tests: surface and water elevations (m) as functions of the column index. */
public final class TestGround {
    /** Cover of test ground (as the old fixtures' stone). */
    public static final Material ROCK = MaterialTable.ANDESITE;
    /** No standing water. */
    public static final Elevation DRY = (x, z) -> Double.NaN;

    /** An elevation (m) per column index; {@code NaN} where absent (no water). */
    @FunctionalInterface
    public interface Elevation {
        double at(int x, int z);
    }

    private TestGround() {}

    /** A terrain over an empty world model of {@code metersPerColumn} columns (default geology). */
    public static TerrainModel terrain(double metersPerColumn) {
        return new TerrainModel(new WorldModel(WorldSpec.withColumns(metersPerColumn)));
    }

    /** Columns {@code [x0, x1] × [z0, z1]} (inclusive) with surface and water elevations (m). */
    public static GroundImport columns(int x0, int z0, int x1, int z1, Elevation surface,
            Elevation water) {
        List<GroundColumn> list = new ArrayList<>();
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                list.add(new GroundColumn(x, z, surface.at(x, z), water.at(x, z), ROCK));
            }
        }
        return new GroundImport(list);
    }

    /** Every column of 16×16-column chunks {@code [cx0, cx1] × [cz0, cz1]} (inclusive). */
    public static GroundImport chunks(int cx0, int cz0, int cx1, int cz1, Elevation surface,
            Elevation water) {
        return columns(cx0 * 16, cz0 * 16, cx1 * 16 + 15, cz1 * 16 + 15, surface, water);
    }

    /** The current ground of a rectangle of columns (surface, water and top material), as a host would re-send it. */
    public static GroundImport copy(WorldModel world, int x0, int z0, int x1, int z1) {
        List<GroundColumn> list = new ArrayList<>();
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (!world.isKnown(x, z)) continue;
                int n = world.layerCount(x, z);
                list.add(new GroundColumn(x, z, world.surfaceZ(x, z), world.waterZ(x, z),
                        world.layer(x, z, n - 1).materialInfo()));
            }
        }
        return new GroundImport(list);
    }
}
