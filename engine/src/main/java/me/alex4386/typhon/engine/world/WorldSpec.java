package me.alex4386.typhon.engine.world;

import java.util.List;
import java.util.Objects;

/**
 * Static description of a world model: grid spacing and the geology new columns are built from.
 *
 * <p>Columns are {@code metersPerColumn} wide: the horizontal resolution of the surface. {@code solverSpacing}
 * is the horizontal spacing of the coarse subsurface solver grid (heat, groundwater).
 *
 * @param metersPerColumn surface resolution {@code dxS} (m)
 * @param solverSpacing coarse solver resolution {@code dxG} (m)
 * @param datumZ bottom elevation of every column (m); nothing exists below
 * @param seaLevelZ sea surface elevation (m), {@code NaN} for no sea
 * @param basement layer cake for imported columns, bottom first; each layer reaches up to its
 *     {@code topZ} (or the surface, if lower)
 * @param edificeMaterial material between the cake and the surface cover of imported columns
 * @param surfaceMaterial cover material of imported columns when the host gives none
 * @param surfaceThickness thickness of that cover (m)
 */
public record WorldSpec(double metersPerColumn, double solverSpacing, double datumZ, double seaLevelZ,
        List<GeologyLayer> basement, String edificeMaterial, String surfaceMaterial, double surfaceThickness) {

    /** One layer of the basement cake. */
    public record GeologyLayer(String material, double topZ, double porosity) {
        public GeologyLayer {
            Objects.requireNonNull(material, "material");
            MaterialTable.require(material);
            if (!(porosity >= 0 && porosity <= 1)) throw new IllegalArgumentException("porosity must be in [0, 1]");
        }
    }

    public WorldSpec {
        if (!(metersPerColumn > 0)) throw new IllegalArgumentException("metersPerColumn must be > 0");
        if (!(solverSpacing >= metersPerColumn)) {
            throw new IllegalArgumentException("solverSpacing must be >= metersPerColumn");
        }
        if (!(surfaceThickness >= 0)) throw new IllegalArgumentException("surfaceThickness must be >= 0");
        basement = List.copyOf(basement);
        MaterialTable.require(edificeMaterial);
        MaterialTable.require(surfaceMaterial);
        double previous = datumZ;
        for (GeologyLayer layer : basement) {
            if (!(layer.topZ() > previous)) {
                throw new IllegalArgumentException("basement layer tops must increase and lie above the datum");
            }
            previous = layer.topZ();
        }
    }

    /**
     * Default world: 10 m columns, a 40 m subsurface solver grid, datum at −2000 m (the bottom of the
     * model, a numerical choice), a granite basement up to −500 m and an andesitic edifice above it (a
     * generic setting; worlds set their own geology), and a {@link #DEFAULT_SURFACE_THICKNESS_M} soil cover.
     */
    public static WorldSpec defaults() {
        return withColumns(10);
    }

    /**
     * Default thickness of the surface cover (m): soil and regolith on hillslopes are typically 0.3–2 m thick
     * (Heimsath et al. 1997, Nature 388; Pelletier et al. 2016, J. Adv. Model. Earth Syst. 8), independent
     * of the grid's resolution.
     */
    public static final double DEFAULT_SURFACE_THICKNESS_M = 1.0;

    /** {@link #defaults()} with columns {@code metersPerColumn} wide (solver grid four columns across). */
    public static WorldSpec withColumns(double metersPerColumn) {
        return new WorldSpec(metersPerColumn, 4 * metersPerColumn, -2000, Double.NaN,
                List.of(new GeologyLayer("granite", -500, 0.01)), "andesite", "soil", DEFAULT_SURFACE_THICKNESS_M);
    }

    /**
     * The same world at another column size; the solver grid keeps its columns-per-solver-column ratio and the
     * geology (including the cover's thickness) is unchanged.
     */
    public WorldSpec withMetersPerColumn(double meters) {
        double spacing = Math.max(meters, solverSpacing * meters / metersPerColumn);
        return new WorldSpec(meters, spacing, datumZ, seaLevelZ, basement, edificeMaterial, surfaceMaterial, surfaceThickness);
    }
}
