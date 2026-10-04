package me.alex4386.typhon.engine.world;

import java.util.List;
import java.util.Objects;

/**
 * Static description of a world model: grid spacing and the geology new columns are built from.
 *
 * <p>Columns are {@code metersPerColumn} wide. In Minecraft mode a column is one block and blocks
 * are cubes, so block {@code y} spans elevations {@code [y·L, (y+1)·L)}. {@code solverSpacing} is the
 * horizontal spacing of the coarse subsurface solver grid (heat, groundwater).
 *
 * @param metersPerColumn surface resolution {@code dxS} (m)
 * @param solverSpacing coarse solver resolution {@code dxG} (m)
 * @param datumZ bottom elevation of every column (m); nothing exists below
 * @param seaLevelZ sea surface elevation (m), {@code NaN} for no sea
 * @param basement layer cake for imported columns, bottom first; each layer reaches up to its
 *     {@code topZ} (or the surface, if lower)
 * @param edificeMaterial material between the cake and the surface cover of imported columns
 * @param surfaceMaterial cover material of imported columns when the host's surface block does not
 *     map to a material
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
     * Minecraft-style default: one-metre cubic columns, datum at −2000 m, a granite basement up to
     * −500 m, andesitic edifice above it and a one-column soil cover.
     */
    public static WorldSpec defaults() {
        return blocks(1.0);
    }

    /** {@link #defaults()} with columns (and blocks) {@code metersPerBlock} wide. */
    public static WorldSpec blocks(double metersPerBlock) {
        return new WorldSpec(metersPerBlock, 4 * metersPerBlock, -2000, Double.NaN,
                List.of(new GeologyLayer("granite", -500, 0.01)), "andesite", "soil", metersPerBlock);
    }

    /** The same world at another column size; the solver grid keeps its columns-per-solver-column ratio. */
    public WorldSpec withMetersPerColumn(double meters) {
        double spacing = Math.max(meters, solverSpacing * meters / metersPerColumn);
        return new WorldSpec(meters, spacing, datumZ, seaLevelZ, basement, edificeMaterial, surfaceMaterial, meters);
    }

    /** Bottom elevation of block {@code y}. */
    public double blockBottom(int y) {
        return y * metersPerColumn;
    }

    /** Top elevation of block {@code y} — the surface elevation when {@code y} is the ground block. */
    public double blockTop(int y) {
        return (y + 1) * metersPerColumn;
    }

    /** The block whose top is the surface at elevation {@code surfaceZ} (inverse of {@link #blockTop}). */
    public int groundBlock(double surfaceZ) {
        return (int) Math.ceil(surfaceZ / metersPerColumn - 1e-6) - 1;
    }
}
