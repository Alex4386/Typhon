package me.alex4386.typhon.engine.world;

/** Bit flags stored per stratigraphic layer. */
public final class LayerFlags {
    /** Unconsolidated: lahars and rain can entrain it. */
    public static final int LOOSE = 1;
    /** Fractured (cooling joints, faulting): raises permeability. */
    public static final int FRACTURED = 1 << 1;
    /** Hydrothermally altered (clay, sulfur): lowers strength and permeability. */
    public static final int ALTERED = 1 << 2;

    private LayerFlags() {}

    /** Default flags for a fresh layer of {@code material}. */
    public static int defaults(Material material) {
        return material.loose() ? LOOSE : 0;
    }
}
