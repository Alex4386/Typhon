package me.alex4386.typhon.engine.lava;

import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;

/** The rock lava turns into, from its composition and cooling history. */
final class LavaRocks {
    private LavaRocks() {}

    /**
     * Crust/roof composition class, one per rock {@link #rockMaterial} distinguishes: 0 basalt (&lt; 52 wt% SiO₂),
     * 1 andesite (52–63), 2 dacite (63–69), 3 rhyolite (a byte of state per column; once the melt is gone the
     * class's representative silica stands for the crust).
     */
    static byte crustKind(double silicaWt) {
        if (silicaWt < 52) return 0;
        if (silicaWt < 63) return 1;
        if (silicaWt < 69) return 2;
        return 3;
    }

    /** Representative SiO₂ (wt%) of a crust class: the middle of its range (basalt and rhyolite: typical). */
    static double crustSilica(byte kind) {
        return switch (kind) {
            case 0 -> 50;
            case 1 -> 57.5;
            case 2 -> 66;
            default -> 72;
        };
    }

    /**
     * Solidified lava: the volcanic rock for its silica, by the TAS boundaries for subalkaline rocks (Le Bas
     * et al. 1986: basalt &lt; 52 wt% SiO₂, basaltic andesite and andesite 52–63, dacite 63–69, rhyolite above;
     * there is no basaltic-andesite material, it is andesite here), or obsidian when a silicic melt was
     * quenched or erupted under water.
     */
    static Material rockMaterial(double silicaWt, boolean submerged, boolean quenched) {
        if (silicaWt >= 63 && (quenched || submerged)) return MaterialTable.OBSIDIAN;
        if (silicaWt < 52) return MaterialTable.BASALT;
        if (silicaWt < 63) return MaterialTable.ANDESITE;
        if (silicaWt < 69) return MaterialTable.DACITE;
        return MaterialTable.RHYOLITE;
    }
}
