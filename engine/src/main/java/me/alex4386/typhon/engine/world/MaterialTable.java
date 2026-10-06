package me.alex4386.typhon.engine.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed set of materials the world model knows. Ids are stable (they are persisted in saves);
 * append new materials at the end only.
 *
 * <p>Values are representative bulk properties, not specific localities. Sources:
 * <ul>
 *   <li>Density, conductivity, heat capacity: Turcotte &amp; Schubert, <i>Geodynamics</i> (3rd ed.,
 *       2014), tables 4.1–4.2; Clauser &amp; Huenges (1995), "Thermal conductivity of rocks and
 *       minerals", AGU Reference Shelf 3; Robertson (1988), USGS Open-File Report 88-441.
 *   <li>Hydraulic conductivity and porosity: Freeze &amp; Cherry, <i>Groundwater</i> (1979), table 2.2;
 *       Ingebritsen, Sanford &amp; Neuzil, <i>Groundwater in Geologic Processes</i> (2006); Hawaiian
 *       basalt flows are among the most permeable rocks known (K ≈ 10⁻⁴–10⁻² m/s, Lachassagne et al.
 *       2014), so fresh basalt gets 10⁻⁵ m/s.
 *   <li>Solidus/liquidus: typical 1 atm values (basalt ≈ 1000–1200 °C, rhyolite ≈ 700–900 °C),
 *       consistent with the magma model's {@code MeltViscosity}.
 *   <li>Erodibility is a relative Typhon calibration (no single literature source).
 * </ul>
 */
public final class MaterialTable {
    private static final List<Material> BY_ID = new ArrayList<>();
    private static final Map<String, Material> BY_NAME = new LinkedHashMap<>();
    private static final double NA = Double.NaN;

    //                                         name             class                  ρ     k     c     logK  φ     sol   liq   ero   loose
    public static final Material AIR = add("air", MaterialClass.AIR, 1.2, 0.026, 1005, 0, 1.0, NA, NA, 0, false);
    public static final Material VOID = add("void", MaterialClass.VOID, 1.2, 0.026, 1005, 0, 1.0, NA, NA, 0, false);
    public static final Material WATER = add("water", MaterialClass.WATER, 1000, 0.6, 4186, NA, 1.0, NA, NA, 0, false);
    public static final Material ICE = add("ice", MaterialClass.ICE, 917, 2.2, 2100, -12, 0.0, 0, 0, 0.3, false);
    public static final Material BASALT = add("basalt", MaterialClass.ROCK, 2600, 1.7, 840, -5, 0.10, 1000, 1200, 0.05, false);
    public static final Material ANDESITE = add("andesite", MaterialClass.ROCK, 2600, 2.0, 840, -7, 0.08, 950, 1150, 0.05, false);
    public static final Material DACITE = add("dacite", MaterialClass.ROCK, 2500, 2.2, 840, -8, 0.06, 850, 1050, 0.05, false);
    public static final Material RHYOLITE = add("rhyolite", MaterialClass.ROCK, 2450, 2.5, 850, -8, 0.05, 750, 950, 0.05, false);
    public static final Material OBSIDIAN = add("obsidian", MaterialClass.ROCK, 2400, 1.3, 840, -10, 0.01, 750, 950, 0.03, false);
    public static final Material SCORIA = add("scoria", MaterialClass.TEPHRA, 1500, 0.5, 900, -3, 0.50, NA, NA, 0.6, true);
    public static final Material TUFF = add("tuff", MaterialClass.TEPHRA, 2000, 1.2, 900, -7, 0.30, NA, NA, 0.2, false);
    public static final Material ASH = add("ash", MaterialClass.TEPHRA, 1000, 0.3, 900, -4, 0.55, NA, NA, 1.0, true);
    public static final Material PUMICE = add("pumice", MaterialClass.TEPHRA, 700, 0.2, 900, -3, 0.70, NA, NA, 0.8, true);
    public static final Material LAHAR_DEPOSIT = add("lahar_deposit", MaterialClass.SOIL, 1900, 1.0, 1000, -5, 0.35, NA, NA, 0.7, true);
    public static final Material HYALOCLASTITE = add("hyaloclastite", MaterialClass.TEPHRA, 2100, 1.0, 900, -5, 0.30, NA, NA, 0.4, false);
    public static final Material GRANITE = add("granite", MaterialClass.ROCK, 2650, 3.0, 790, -10, 0.01, 650, 1000, 0.01, false);
    public static final Material GABBRO = add("gabbro", MaterialClass.ROCK, 3000, 2.2, 840, -10, 0.01, 1000, 1200, 0.01, false);
    public static final Material SEDIMENT = add("sediment", MaterialClass.ROCK, 2400, 2.5, 900, -6, 0.15, NA, NA, 0.3, false);
    public static final Material SOIL = add("soil", MaterialClass.SOIL, 1500, 0.8, 1200, -5, 0.45, NA, NA, 0.8, true);
    public static final Material CLAY = add("clay", MaterialClass.SOIL, 1800, 1.2, 1100, -9, 0.40, NA, NA, 0.5, false);
    public static final Material SULFUR = add("sulfur", MaterialClass.ROCK, 2000, 0.27, 710, -8, 0.10, 115, 115, 0.5, false);
    /**
     * Glacial outwash / alluvial sand and gravel, e.g. the permeable fill of Yellowstone's geyser
     * basins (White et al. 1975, USGS PP 892): K ≈ 10⁻⁴–10⁻² m/s (Freeze &amp; Cherry 1979, Table 2.2).
     */
    public static final Material GRAVEL = add("gravel", MaterialClass.SOIL, 2000, 1.5, 900, -3.5, 0.30, NA, NA, 0.6, true);
    /**
     * Unsorted rock debris of landslides, debris avalanches and explosion breccia: a poorly sorted
     * block-in-matrix mixture, bulk density ≈ 2000 kg/m³ and porosity ≈ 0.3 (Glicken 1996, USGS OFR
     * 96-677, for the 1980 Mount St. Helens avalanche deposit).
     */
    public static final Material DEBRIS = add("debris", MaterialClass.SOIL, 2000, 1.2, 900, -4, 0.30, NA, NA, 0.5, true);

    private MaterialTable() {}

    private static Material add(String name, MaterialClass cls, double density, double k, double c, double logK,
            double porosity, double solidus, double liquidus, double erodibility, boolean loose) {
        Material m = new Material((short) BY_ID.size(), name, cls, density, k, c, logK, porosity, solidus, liquidus,
                erodibility, loose);
        BY_ID.add(m);
        BY_NAME.put(name, m);
        return m;
    }

    /**
     * Angle of repose of loose deposits of {@code m} (°): scoria and cinder cones 30–35° (Porter 1972, GSA
     * Bull. 83; Bemis &amp; Ferencz 2017), angular talus and rock-avalanche debris 35–40°, fine ash ≈ 33°.
     * The single source for slope stability and the repose relaxation of fresh deposits.
     */
    public static double reposeAngleDeg(Material m) {
        if (m == SCORIA) return 34;
        if (m == PUMICE) return 35;
        if (m == ASH) return 33;
        if (m == GRAVEL) return 35;
        if (m == DEBRIS) return 37;
        if (m == LAHAR_DEPOSIT) return 33;
        if (m == SOIL) return 30;
        if (m == CLAY) return 18;
        if (m == ICE) return 30;
        if (m.materialClass() == MaterialClass.ROCK) return 37; // angular talus of broken rock
        return 33;
    }

    public static Material get(int id) {
        if (id < 0 || id >= BY_ID.size()) throw new IllegalArgumentException("Unknown material id " + id);
        return BY_ID.get(id);
    }

    /** Material by configuration name, or {@code null}. */
    public static Material byName(String name) {
        return BY_NAME.get(name);
    }

    /** Material by name; throws listing the known names if unknown. */
    public static Material require(String name) {
        Material m = BY_NAME.get(name);
        if (m == null) throw new IllegalArgumentException("Unknown material '" + name + "'; known: " + BY_NAME.keySet());
        return m;
    }

    public static List<Material> all() {
        return Collections.unmodifiableList(BY_ID);
    }

    public static int size() {
        return BY_ID.size();
    }

    /** Extrusive rock for a silica content (wt% SiO₂), using the usual TAS boundaries. */
    public static Material extrusiveForSilica(double silicaWt) {
        if (silicaWt < 52) return BASALT;
        if (silicaWt < 63) return ANDESITE;
        if (silicaWt < 69) return DACITE;
        return RHYOLITE;
    }
}
