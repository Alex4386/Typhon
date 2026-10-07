package me.alex4386.typhon.simulator.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;

/**
 * Built-in scenarios: real volcanoes at real scale ({@link RealPresets}). Magma properties (composition,
 * temperature, water, reservoir depth, supply and eruption rates) are taken from the literature values
 * listed in each preset's {@link Preset#references()}. Every preset starts close to the state that makes
 * its signature behaviour happen soon (e.g. a chamber just below failure).
 */
public final class Presets {
    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    static {
        for (Preset real : RealPresets.all()) PRESETS.put(real.name(), real);
    }

    private Presets() {}

    public static List<Preset> all() {
        return List.copyOf(PRESETS.values());
    }

    public static Preset get(String name) {
        Preset preset = PRESETS.get(name);
        if (preset == null) throw new IllegalArgumentException("Unknown preset '" + name + "'. Known: " + PRESETS.keySet());
        return preset;
    }

    /** Build step of a preset: terrain → scenario builder (engine options are applied afterwards). */
    @FunctionalInterface
    interface Assembly {
        Scenario.Builder build(long seed, ColumnGrid terrain);
    }

    /** Wind variability used by every preset. */
    static final double WIND_VARIABILITY = 0.3;
}
