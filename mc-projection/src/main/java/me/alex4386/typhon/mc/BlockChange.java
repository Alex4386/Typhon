package me.alex4386.typhon.mc;

import java.util.Objects;

/**
 * A block mutation for a host that shows the world as blocks (computed by {@link BlockProjection}).
 *
 * <p>{@code expected} enables compare-and-set application: hosts skip the change (and report a
 * conflict back to the engine) when the live block's id no longer matches, e.g. because a player
 * built there after the terrain snapshot was taken. Only the id is compared, so unrelated property
 * changes (waterlogging, orientation) do not cause conflicts. {@code null} means "apply
 * unconditionally".
 */
public record BlockChange(BlockPos pos, BlockId expected, BlockState to) {
    public BlockChange {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(to, "to");
    }

    public static BlockChange set(BlockPos pos, BlockState to) {
        return new BlockChange(pos, null, to);
    }

    public static BlockChange set(BlockPos pos, BlockId to) {
        return set(pos, BlockState.of(to));
    }

    public static BlockChange replace(BlockPos pos, BlockId expected, BlockState to) {
        return new BlockChange(pos, expected, to);
    }

    public static BlockChange replace(BlockPos pos, BlockId expected, BlockId to) {
        return replace(pos, expected, BlockState.of(to));
    }

    public boolean isConditional() {
        return expected != null;
    }
}
