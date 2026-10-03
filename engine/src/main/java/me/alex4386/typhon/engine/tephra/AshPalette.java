package me.alex4386.typhon.engine.tephra;

import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * How tephra deposit thickness is shown in blocks.
 *
 * <p>Thin deposits replace the surface block in place ({@link #covers}, by increasing thickness);
 * each full {@link #blockThickness} of deposit adds one {@link #wholeBlock} on top of the column and
 * raises the ground.
 */
public record AshPalette(List<Cover> covers, double blockThickness, BlockId wholeBlock) {
    /** Replace the surface with {@code block} once the deposit is at least {@code minThickness} m. */
    public record Cover(double minThickness, BlockId block) {
        public Cover {
            Objects.requireNonNull(block, "block");
        }
    }

    public AshPalette {
        covers = List.copyOf(covers);
        for (int i = 1; i < covers.size(); i++) {
            if (covers.get(i).minThickness() <= covers.get(i - 1).minThickness()) {
                throw new IllegalArgumentException("Cover thresholds must increase");
            }
        }
        if (!(blockThickness > 0)) throw new IllegalArgumentException("blockThickness must be positive");
        Objects.requireNonNull(wholeBlock, "wholeBlock");
    }

    public static AshPalette defaults() {
        return new AshPalette(
                List.of(
                        new Cover(0.02, BlockId.minecraft("coarse_dirt")),
                        new Cover(0.10, BlockId.minecraft("gravel")),
                        new Cover(0.35, BlockId.minecraft("light_gray_concrete_powder"))),
                1.0,
                BlockId.minecraft("tuff"));
    }

    /** Index of the thickest cover reached, or -1 if none. */
    public int coverStage(double thickness) {
        int stage = -1;
        for (int i = 0; i < covers.size(); i++) {
            if (thickness >= covers.get(i).minThickness()) stage = i;
        }
        return stage;
    }

    public int wholeBlocks(double thickness) {
        return (int) Math.floor(thickness / blockThickness);
    }

    /** Thinnest thickness that changes any block. */
    public double minimumVisibleThickness() {
        return covers.isEmpty() ? blockThickness : covers.get(0).minThickness();
    }
}
