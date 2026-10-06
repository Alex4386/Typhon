package me.alex4386.typhon.engine.magma.plumbing;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;

/**
 * A volcano's magma plumbing beyond its main (eruptive) chamber: further chambers (deeper or side
 * reservoirs) and the pathways between all of them. Empty for the classic single-chamber volcano.
 *
 * <p>Multi-level storage is the rule at active volcanoes: Kīlauea's summit has the Halemaʻumaʻu
 * reservoir at ~1–2 km fed from the South Caldera reservoir at ~3–5 km (Poland, Miklius &amp;
 * Montgomery-Brown 2014, USGS PP 1801); arc volcanoes tap vertically extensive, mush-dominated
 * systems of stacked melt lenses (Cashman, Sparks &amp; Blundy 2017, Science 355).
 *
 * @param chambers the extra chambers (ids other than {@link MagmaChamberConfig#MAIN})
 * @param connections pathways between any two chambers, the main one included
 */
public record PlumbingConfig(List<MagmaChamberConfig> chambers, List<ConnectionConfig> connections) {
    public static final PlumbingConfig NONE = new PlumbingConfig(List.of(), List.of());

    public PlumbingConfig {
        chambers = List.copyOf(chambers);
        connections = List.copyOf(connections);
        Set<String> ids = new HashSet<>();
        ids.add(MagmaChamberConfig.MAIN);
        for (MagmaChamberConfig c : chambers) {
            if (!ids.add(c.chamberId())) throw new IllegalArgumentException("duplicate chamber id " + c.chamberId());
        }
        Set<String> links = new HashSet<>();
        for (ConnectionConfig c : connections) {
            if (!links.add(c.id())) throw new IllegalArgumentException("duplicate connection id " + c.id());
            if (!ids.contains(c.from())) throw new IllegalArgumentException("connection " + c.id() + ": no chamber " + c.from());
            if (!ids.contains(c.to())) throw new IllegalArgumentException("connection " + c.id() + ": no chamber " + c.to());
        }
    }

    public boolean isEmpty() {
        return chambers.isEmpty() && connections.isEmpty();
    }

    /** The extra chamber {@code id}, or null. */
    public MagmaChamberConfig chamber(String id) {
        for (MagmaChamberConfig c : chambers) if (c.chamberId().equals(id)) return c;
        return null;
    }
}
