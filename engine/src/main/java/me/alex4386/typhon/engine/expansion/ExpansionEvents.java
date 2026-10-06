package me.alex4386.typhon.engine.expansion;

import java.util.List;
import me.alex4386.typhon.engine.output.HistoricalEvent;

/** Events of {@link WorldExpansion}. */
public final class ExpansionEvents {
    private ExpansionEvents() {}

    /** An expansion tile: columns {@code [x·size, (x+1)·size) × [z·size, (z+1)·size)}. */
    public record TileCoord(int x, int z) {}

    /**
     * The simulated area grew: {@code tiles} were materialised from the terrain generator this step.
     *
     * @param tileColumns side of a tile in columns
     * @param addedTiles tiles expansion has added in total (this world's lifetime)
     * @param simulatedAreaKm2 simulated area after the growth (km²)
     */
    public record AreaExpanded(double time, List<TileCoord> tiles, int tileColumns, int addedTiles,
            double simulatedAreaKm2) implements HistoricalEvent {
        public AreaExpanded {
            tiles = List.copyOf(tiles);
        }
    }
}
