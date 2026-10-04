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

    /** Simulated hours that show the scenario's main behaviour. */
    double defaultHours();

    /** The preset's own synthetic terrain. */
    ColumnGrid terrain(long seed);

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
