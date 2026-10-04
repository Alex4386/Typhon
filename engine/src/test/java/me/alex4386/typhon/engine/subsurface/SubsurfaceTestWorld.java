package me.alex4386.typhon.engine.subsurface;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;

/** Builders for small world models used by the subsurface tests. */
final class SubsurfaceTestWorld {
    private SubsurfaceTestWorld() {}

    interface Surface {
        double z(int x, int z);
    }

    /** Uniform geology of one material, columns {@code [0, nx) × [0, nz)}. */
    static WorldModel uniform(String material, double metersPerColumn, double solverSpacing, double seaLevel, int nx,
            int nz, Surface surface) {
        WorldSpec spec = new WorldSpec(metersPerColumn, solverSpacing, -5000, seaLevel, List.of(), material, material,
                1);
        return build(spec, nx, nz, surface);
    }

    static WorldModel build(WorldSpec spec, int nx, int nz, Surface surface) {
        WorldModel world = new WorldModel(spec);
        List<WorldModel.ColumnImport> columns = new ArrayList<>();
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                columns.add(new WorldModel.ColumnImport(x, z, surface.z(x, z),
                        MaterialTable.require(spec.surfaceMaterial())));
            }
        }
        world.importColumns(columns);
        return world;
    }

    static SubsurfaceConfig config() {
        SubsurfaceConfig c = new SubsurfaceConfig();
        c.surfaceTemperatureC = 0;
        c.gradientCPerKm = 0;
        c.evaporationMmPerHour = 0;
        return c;
    }
}
