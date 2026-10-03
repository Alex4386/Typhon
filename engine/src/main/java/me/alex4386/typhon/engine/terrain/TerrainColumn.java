package me.alex4386.typhon.engine.terrain;

import java.util.Objects;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * Surface description of one x/z column.
 *
 * @param groundY y of the highest solid (non-fluid, non-plant) block
 * @param waterY y of the highest water block above the ground, or {@link #NO_WATER}
 * @param surface block id at {@code groundY}
 */
public record TerrainColumn(int groundY, int waterY, BlockId surface) {
    public static final int NO_WATER = Integer.MIN_VALUE;

    public TerrainColumn {
        Objects.requireNonNull(surface, "surface");
    }

    public static TerrainColumn dry(int groundY, BlockId surface) {
        return new TerrainColumn(groundY, NO_WATER, surface);
    }

    public boolean submerged() {
        return waterY != NO_WATER && waterY > groundY;
    }

    /** Water depth above the ground in blocks (0 when dry). */
    public int waterDepth() {
        return submerged() ? waterY - groundY : 0;
    }
}
