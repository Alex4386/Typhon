package me.alex4386.typhon.engine.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A magma chamber placed by the user into a world: the definition of a new volcano with nothing built
 * yet. The chamber sits {@code depthM} below the ground at a column; its vent is {@link
 * me.alex4386.typhon.engine.volcano.VentSite#emergent emergent}, where the conduit from the chamber will
 * meet the ground, with no crater carved and no edifice. Cones, craters, islands and fissures come from
 * what the eruptions do. Fields left out take the defaults below (the server reports them in its schema).
 */
public final class ChamberPlacement {
    private ChamberPlacement() {}

    /**
     * A placement request; {@code null} optional fields take the {@link Field#defaultValue defaults}.
     *
     * @param x column of the chamber (and vent)
     * @param z column of the chamber (and vent)
     * @param depthM depth of the chamber centre below the ground (m)
     */
    public record Request(String name, int x, int z, double depthM, Double volumeM3, Double temperatureC, Double silicaWt,
            Double waterWt, Double co2Wt, Double crystalFraction, Double supplyRateM3PerS, Double tensileStrengthMPa,
            Double initialOverpressureMPa) {
        public Request {
            if (!(depthM >= 200 && depthM <= 20_000)) throw new IllegalArgumentException("depthM must be within 200–20000 m");
        }
    }

    /** A placement field with its default, range and meaning (for the server's schema). */
    public record Field(String id, String label, String unit, double defaultValue, double min, double max, boolean log,
            String help) {}

    /**
     * The fields of a placement. Defaults describe a shallow alkali-basalt reservoir like the one that
     * built Surtsey (SiO₂ ~46–47 wt%, 1150–1180 °C, H₂O < 1 wt%; Jakobsson et al. 2000), a cubic kilometre
     * fed at ~0.06 km³/yr, starting unpressurised: it erupts once recharge breaks its roof.
     */
    public static final List<Field> FIELDS = List.of(
            new Field("depthM", "Depth below the ground", "m", 3000, 200, 20_000, false,
                    "Depth of the chamber centre below the ground (sea floor) at the chosen point."),
            new Field("volumeM3", "Chamber volume", "m³", 1e9, 1e7, 1e12, true, "Size of the magma reservoir."),
            new Field("temperatureC", "Magma temperature", "°C", 1170, 650, 1350, false, null),
            new Field("silicaWt", "Silica (SiO₂)", "wt%", 46.5, 42, 78, false,
                    "Basalt ~46–52, andesite ~60, rhyolite ~74: more silica, stickier and more explosive magma."),
            new Field("waterWt", "Water (H₂O)", "wt%", 0.7, 0, 8, false, "Dissolved water drives explosions as it exsolves."),
            new Field("co2Wt", "Carbon dioxide (CO₂)", "wt%", 0.2, 0, 3, false, null),
            new Field("crystalFraction", "Crystals", "fraction", 0, 0, 0.6, false, null),
            new Field("supplyRateM3PerS", "Deep magma supply", "m³/s", 2, 0, 50, true,
                    "Magma arriving from depth; Kīlauea ~3–6 m³/s, Etna ~1."),
            new Field("tensileStrengthMPa", "Roof strength", "MPa", 12, 0.5, 100, true,
                    "Overpressure that breaks the rock above the chamber and starts an eruption."),
            new Field("initialOverpressureMPa", "Starting overpressure", "MPa", 0, 0, 100, false,
                    "0: a new chamber that must be recharged before it can erupt."));

    static double value(Double requested, String field) {
        if (requested != null) return requested;
        for (Field f : FIELDS) if (f.id().equals(field)) return f.defaultValue();
        throw new IllegalArgumentException("unknown field " + field);
    }

    /** The default of a field. */
    public static double defaultOf(String field) {
        return value(null, field);
    }

    /**
     * The definition of the new volcano {@code id} for {@code r}, with the ground at {@code groundZ} (m) at
     * the chosen column, in a world of {@code metersPerColumn} columns.
     */
    public static VolcanoDefinition definition(String id, Request r, double groundZ, double metersPerColumn) {
        double l = metersPerColumn;
        int ventY = block(groundZ, l);
        int chamberY = block(groundZ - r.depthM(), l);
        double temperature = value(r.temperatureC(), "temperatureC");
        double silica = value(r.silicaWt(), "silicaWt");
        double water = value(r.waterWt(), "waterWt");
        double co2 = value(r.co2Wt(), "co2Wt");
        double crystals = value(r.crystalFraction(), "crystalFraction");

        Map<String, Object> vent = new LinkedHashMap<>();
        vent.put("id", "vent");
        vent.put("kind", "crater");
        vent.put("x", r.x());
        vent.put("y", ventY);
        vent.put("z", r.z());
        // an opening a conduit's width across (~20 m); the crater widens from what the eruption excavates
        vent.put("radius", Math.max(1, (int) Math.round(20 / l)));
        vent.put("emergent", true);
        List<Object> vents = new ArrayList<>();
        vents.add(vent);

        Map<String, Object> chamber = new LinkedHashMap<>();
        chamber.put("center", WorldDefinition.map("x", r.x(), "y", chamberY, "z", r.z()));
        chamber.put("volume", value(r.volumeM3(), "volumeM3"));
        chamber.put("lithostaticDepth", r.depthM());
        chamber.put("tensileStrengthMPa", value(r.tensileStrengthMPa(), "tensileStrengthMPa"));
        chamber.put("supplyRate", value(r.supplyRateM3PerS(), "supplyRateM3PerS"));
        chamber.put("initialOverpressureMPa", value(r.initialOverpressureMPa(), "initialOverpressureMPa"));
        chamber.put("initialTemperatureC", temperature);
        chamber.put("rechargeTemperatureC", temperature);
        chamber.put("initialSilicaWt", silica);
        chamber.put("rechargeSilicaWt", silica);
        chamber.put("initialWaterWt", water);
        chamber.put("rechargeWaterWt", water);
        chamber.put("initialCo2Wt", co2);
        chamber.put("rechargeCo2Wt", co2);
        chamber.put("rechargeCrystalFraction", crystals);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", r.name() != null && !r.name().isBlank() ? r.name() : id);
        root.put("vents", vents);
        root.put("magma", WorldDefinition.map("chamber", chamber));
        return VolcanoDefinition.parse(id, ConfigNode.root("volcanoes/" + id + ".yaml", root));
    }

    /**
     * A further chamber of an existing volcano's plumbing for {@code r}: the {@code magma.chambers} element
     * (YAML tree) at column ({@code r.x}, {@code r.z}), {@code r.depthM} below ground at {@code groundZ}.
     * Fields left unset are not written, so they follow the volcano's main chamber; it gets no deep supply
     * unless {@code r} sets one. The server's one mapping from placement fields to definitions.
     */
    public static Map<String, Object> chamberElement(String chamberId, Request r, double groundZ, double metersPerColumn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", chamberId);
        m.put("center", WorldDefinition.map("x", r.x(), "y", block(groundZ - r.depthM(), metersPerColumn), "z", r.z()));
        m.put("lithostaticDepth", r.depthM());
        if (r.volumeM3() != null) m.put("volume", r.volumeM3());
        if (r.tensileStrengthMPa() != null) m.put("tensileStrengthMPa", r.tensileStrengthMPa());
        m.put("supplyRate", r.supplyRateM3PerS() != null ? r.supplyRateM3PerS() : 0.0);
        if (r.initialOverpressureMPa() != null) m.put("initialOverpressureMPa", r.initialOverpressureMPa());
        if (r.temperatureC() != null) {
            m.put("initialTemperatureC", r.temperatureC());
            m.put("rechargeTemperatureC", r.temperatureC());
        }
        if (r.silicaWt() != null) {
            m.put("initialSilicaWt", r.silicaWt());
            m.put("rechargeSilicaWt", r.silicaWt());
        }
        if (r.waterWt() != null) {
            m.put("initialWaterWt", r.waterWt());
            m.put("rechargeWaterWt", r.waterWt());
        }
        if (r.co2Wt() != null) {
            m.put("initialCo2Wt", r.co2Wt());
            m.put("rechargeCo2Wt", r.co2Wt());
        }
        if (r.crystalFraction() != null) m.put("rechargeCrystalFraction", r.crystalFraction());
        return m;
    }

    /** A {@code magma.connections} element (YAML tree): a conduit or dike from chamber {@code from} to {@code to}. */
    public static Map<String, Object> connectionElement(String id, String from, String to, boolean dike, Double radiusM, Double widthM) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("from", from);
        m.put("to", to);
        m.put("kind", dike ? "dike" : "conduit");
        if (radiusM != null) m.put("radiusM", radiusM);
        if (widthM != null) m.put("widthM", widthM);
        return m;
    }

    /** Index of the block whose top is at or just above {@code z} (m). */
    static int block(double z, double l) {
        return (int) Math.ceil(z / l - 1e-6) - 1;
    }
}
