package me.alex4386.typhon.simulator.scenario;

import java.util.List;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;

/** A named, documented scenario inspired by a real volcano. */
public interface Preset {
    /** Short CLI name, e.g. {@code kilauea}. */
    String name();

    String title();

    /** What the scenario shows and how it is set up. */
    String description();

    /** Real-world reference values the parameters are based on (one line each). */
    List<String> references();

    /** Hours that show the scenario's main behaviour. */
    double defaultHours();

    /** The preset's real-world setting (geology, domain, DEM source), or {@code null} for a world run. */
    default RealSetting realSetting() {
        return null;
    }

    /** Hours the validation suite runs this preset for (its reference horizon). */
    default double validationHours() {
        return defaultHours();
    }

    /** Published values the run is compared with in the report ("reference vs model"). */
    default List<ReferenceValue> referenceValues() {
        return List.of();
    }

    /** The preset's own synthetic terrain. */
    ColumnGrid terrain(long seed);

    /**
     * The preset's landscape over a centred window {@code 2·halfExtentColumns} columns wide (rounded up to
     * whole tiles): the same generator as {@link #terrain(long)}, so any window agrees column for column.
     */
    default ColumnGrid terrain(long seed, int halfExtentColumns) {
        ColumnGrid grid = terrain(seed);
        if (grid.source() == null || grid.size() == 2 * ColumnGrid.roundHalf(halfExtentColumns)) return grid;
        return grid.window(halfExtentColumns);
    }

    /**
     * Width (m) of the simulated core of a world written from this preset ({@code terrain.coreExtentM});
     * {@code NaN} keeps {@link #terrain(long)}'s window. Larger than the preset's own run window where the
     * real setting deserves it (the world grows further on demand).
     */
    default double worldCoreExtentM() {
        return Double.NaN;
    }

    /**
     * Builds the scenario on {@code terrain} (normally {@link #terrain(long)}; a DEM may replace it,
     * in which case vents are re-anchored to the new ground).
     */
    default Scenario build(long seed, ColumnGrid terrain) {
        return build(seed, terrain, Scenario.Options.DEFAULT);
    }

    /** Builds the scenario with explicit engine options (base step, resume from a save). */
    Scenario build(long seed, ColumnGrid terrain, Scenario.Options options);

    default Scenario build(long seed) {
        return build(seed, terrain(seed));
    }
}
