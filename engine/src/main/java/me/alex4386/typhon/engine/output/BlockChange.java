package me.alex4386.typhon.engine.output;

import java.util.Objects;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * A block mutation requested by the engine.
 *
 * <p>{@code expected} enables compare-and-set application: hosts skip the change (and report a
 * conflict back to the engine) when the live block no longer matches, e.g. because a player built
 * there after the terrain snapshot was taken. {@code null} means "apply unconditionally".
 */
public record BlockChange(BlockPos pos, BlockId expected, BlockId to) {
    public BlockChange {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(to, "to");
    }

    public static BlockChange set(BlockPos pos, BlockId to) {
        return new BlockChange(pos, null, to);
    }

    public static BlockChange replace(BlockPos pos, BlockId expected, BlockId to) {
        return new BlockChange(pos, expected, to);
    }

    public boolean isConditional() {
        return expected != null;
    }
}
