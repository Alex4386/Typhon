package me.alex4386.typhon.engine.expansion;

/**
 * On-demand growth of the simulated area ({@code expansion:} in {@code world.yaml}).
 *
 * <pre>{@code
 * expansion:
 *   enabled: true
 *   tileColumns: 64          # expansion unit: square tiles of this many columns, aligned to multiples of it
 *   marginTiles: 2           # activity this close (tiles) to unsimulated ground materialises it
 *   maxExtentM: 40000        # hard cap: tiles must lie within a square this wide around x = z = 0
 *   maxTiles: 400            # hard cap on tiles added by expansion (memory bound)
 *   periodSeconds: 1         # how often (s) activity is checked
 *   ashThresholdM: 0.01      # tephra fall at least this thick (m) counts as activity
 *   waterThresholdM: 0.25    # moving surface water at least this deep (m) counts as activity
 * }</pre>
 *
 * Every parameter is hot-reloadable: it only decides which tiles are materialised from now on.
 *
 * @param enabled whether the simulated area grows at all (it also needs a host terrain generator)
 * @param tileColumns side of an expansion tile in columns (a multiple of 16)
 * @param marginTiles Chebyshev distance (tiles) around active ground that must be simulated
 * @param maxExtentM width (m) of the square around the world origin that expansion stays within
 * @param maxTiles maximum number of tiles expansion may add
 * @param periodSeconds activity check period (seconds)
 * @param ashThresholdM tephra deposit thickness (m) that makes an area active ({@code Infinity} = never)
 * @param waterThresholdM moving surface-water depth (m) that makes an area active ({@code Infinity} = never)
 */
public record ExpansionConfig(boolean enabled, int tileColumns, int marginTiles, double maxExtentM, int maxTiles,
        double periodSeconds, double ashThresholdM, double waterThresholdM) {
    public static final ExpansionConfig DEFAULTS = new ExpansionConfig(true, 64, 2, 40_000, 400, 1, 0.01, 0.25);
    /** No growth. */
    public static final ExpansionConfig DISABLED = new ExpansionConfig(false, 64, 2, 40_000, 400, 1, 0.01, 0.25);

    public ExpansionConfig {
        if (tileColumns <= 0 || tileColumns % 16 != 0) {
            throw new IllegalArgumentException("tileColumns must be a positive multiple of 16: " + tileColumns);
        }
        if (marginTiles < 0) throw new IllegalArgumentException("marginTiles must be >= 0");
        if (!(maxExtentM > 0)) throw new IllegalArgumentException("maxExtentM must be positive");
        if (maxTiles < 0) throw new IllegalArgumentException("maxTiles must be >= 0");
        if (!(periodSeconds > 0)) throw new IllegalArgumentException("periodSeconds must be positive");
        if (!(ashThresholdM > 0)) throw new IllegalArgumentException("ashThresholdM must be positive");
        if (!(waterThresholdM > 0)) throw new IllegalArgumentException("waterThresholdM must be positive");
    }
}
