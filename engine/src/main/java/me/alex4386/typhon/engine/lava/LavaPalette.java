package me.alex4386.typhon.engine.lava;

import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;

/** Every block {@link LavaFlow} places, chosen from composition and cooling history. */
public final class LavaPalette {
    public static final BlockState AIR = BlockState.AIR;
    public static final BlockState WATER = BlockState.minecraft("water");
    public static final BlockState MAGMA_CRUST = BlockState.minecraft("magma_block");

    private static final BlockState BASALT_COLUMN = BlockState.minecraft("basalt").with("axis", "y");
    private static final BlockState POLISHED_BASALT = BlockState.minecraft("polished_basalt").with("axis", "y");
    private static final BlockState SMOOTH_BASALT = BlockState.minecraft("smooth_basalt");
    private static final BlockState BLACKSTONE = BlockState.minecraft("blackstone");
    private static final BlockState DEEPSLATE = BlockState.minecraft("deepslate").with("axis", "y");
    private static final BlockState TUFF = BlockState.minecraft("tuff");
    private static final BlockState ANDESITE = BlockState.minecraft("andesite");
    private static final BlockState STONE = BlockState.minecraft("stone");
    private static final BlockState COBBLESTONE = BlockState.minecraft("cobblestone");
    private static final BlockState GRANITE = BlockState.minecraft("granite");
    private static final BlockState OBSIDIAN = BlockState.minecraft("obsidian");
    private static final BlockState CRYING_OBSIDIAN = BlockState.minecraft("crying_obsidian");

    /** Quench-shattered glassy fragments shed down a lava delta front. */
    public static final BlockState HYALOCLASTITE = TUFF;

    private LavaPalette() {}

    /** Crust/roof composition class: 0 basaltic, 1 andesitic, 2 silicic. */
    public static byte crustKind(double silicaWt) {
        if (silicaWt < 57) return 0;
        if (silicaWt < 65) return 1;
        return 2;
    }

    /** Representative SiO₂ of a crust class, for rock choices once the melt is gone. */
    public static double crustSilica(byte kind) {
        return switch (kind) {
            case 0 -> 50;
            case 1 -> 60;
            default -> 70;
        };
    }

    /** Solid crust / tube roof: smooth glassy pāhoehoe skin, or a silicic glassy carapace. */
    public static BlockState roof(byte kind) {
        return switch (kind) {
            case 0 -> SMOOTH_BASALT;
            case 1 -> ANDESITE;
            default -> OBSIDIAN;
        };
    }

    /** Molten lava; {@code level} 0 is a full block, 7 the thinnest film. */
    public static BlockState lava(int level) {
        return BlockState.minecraft("lava").with("level", level);
    }

    /**
     * Rock formed when a column solidifies.
     *
     * @param submerged cooled under water (pillow lava / hyaloclastite)
     * @param quenched cooled faster than the glass-forming threshold
     * @param columnar thick, slowly cooled flow (columnar jointing)
     */
    public static BlockState rock(
            double silicaWt, boolean submerged, boolean quenched, boolean columnar, SimRandom random) {
        if (silicaWt < 45) {
            return random.chance(0.3) ? DEEPSLATE : BLACKSTONE;
        }
        if (silicaWt < 53) {
            return basaltic(submerged, quenched, columnar, random);
        }
        if (silicaWt < 57) {
            double ratio = (silicaWt - 53) / 4;
            return random.chance(ratio)
                    ? andesitic(submerged, quenched, random)
                    : basaltic(submerged, quenched, columnar, random);
        }
        if (silicaWt < 65) {
            return andesitic(submerged, quenched, random);
        }
        if (silicaWt < 69) {
            if (quenched || submerged) return glass(random);
            double ratio = (silicaWt - 65) / 4;
            if (!random.chance(ratio)) return andesitic(false, false, random);
            if (random.chance(0.1)) return GRANITE;
            return random.chance(0.3) ? COBBLESTONE : STONE;
        }
        if (quenched || submerged) return glass(random);
        return random.chance(0.6) ? GRANITE : STONE;
    }

    /**
     * World-model material of solidified lava: the volcanic rock for its silica (basalt &lt; 53 wt%,
     * andesite &lt; 63, dacite &lt; 69, rhyolite above), or obsidian when a silicic melt was quenched
     * or erupted under water.
     */
    public static Material rockMaterial(double silicaWt, boolean submerged, boolean quenched) {
        if (silicaWt >= 63 && (quenched || submerged)) return MaterialTable.OBSIDIAN;
        if (silicaWt < 53) return MaterialTable.BASALT;
        if (silicaWt < 63) return MaterialTable.ANDESITE;
        if (silicaWt < 69) return MaterialTable.DACITE;
        return MaterialTable.RHYOLITE;
    }

    private static BlockState glass(SimRandom random) {
        return random.chance(0.9) ? OBSIDIAN : CRYING_OBSIDIAN;
    }

    private static BlockState basaltic(boolean submerged, boolean quenched, boolean columnar, SimRandom random) {
        if (submerged) return random.chance(0.3) ? TUFF : SMOOTH_BASALT;
        if (columnar) return BASALT_COLUMN;
        if (quenched) return SMOOTH_BASALT;
        return random.chance(0.5) ? POLISHED_BASALT : BASALT_COLUMN;
    }

    private static BlockState andesitic(boolean submerged, boolean quenched, SimRandom random) {
        if (submerged || quenched) return TUFF;
        return random.chance(0.3) ? TUFF : ANDESITE;
    }
}
