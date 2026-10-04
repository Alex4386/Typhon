package me.alex4386.typhon.engine.world;

/** Broad behaviour class of a {@link Material}. */
public enum MaterialClass {
    /** Open air above the ground surface (also "no data"). */
    AIR,
    /** A cavity below the surface: lava tube, dug tunnel, collapse pit. */
    VOID,
    /** Standing water body (lake, sea) — surface water proper lives in its own field. */
    WATER,
    ICE,
    /** Coherent rock: lava flows, intrusions, basement, welded tuff. */
    ROCK,
    /** Pyroclastic material: ash, lapilli, scoria, pumice, ignimbrite. */
    TEPHRA,
    /** Soil and reworked sediment (lahar deposits, alluvium). */
    SOIL;

    /** Whether material of this class counts as ground ("is there rock here?"). */
    public boolean solid() {
        return this == ICE || this == ROCK || this == TEPHRA || this == SOIL;
    }
}
