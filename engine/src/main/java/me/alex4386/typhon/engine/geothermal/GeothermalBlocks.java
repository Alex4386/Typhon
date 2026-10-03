package me.alex4386.typhon.engine.geothermal;

import java.util.Set;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * Block ids used by the geothermal subsystem and their default fallback chains.
 *
 * <p>Sulfur, potent sulfur, sulfur spikes and cinnabar were added in Minecraft 26.2 ("Chaos Cubed").
 * Property names of {@code sulfur_spike} are assumed to mirror {@code pointed_dripstone}
 * ({@code vertical_direction}, {@code thickness}).
 */
public final class GeothermalBlocks {
    public static final BlockId AIR = BlockId.AIR;
    public static final BlockId WATER = BlockId.minecraft("water");
    public static final BlockId MAGMA_BLOCK = BlockId.minecraft("magma_block");
    public static final BlockId SULFUR = BlockId.minecraft("sulfur");
    public static final BlockId POTENT_SULFUR = BlockId.minecraft("potent_sulfur");
    public static final BlockId SULFUR_SPIKE = BlockId.minecraft("sulfur_spike");
    public static final BlockId POINTED_DRIPSTONE = BlockId.minecraft("pointed_dripstone");
    public static final BlockId CINNABAR = BlockId.minecraft("cinnabar");
    public static final BlockId CALCITE = BlockId.minecraft("calcite");
    public static final BlockId DIORITE = BlockId.minecraft("diorite");
    public static final BlockId MUD = BlockId.minecraft("mud");
    public static final BlockId CLAY = BlockId.minecraft("clay");
    public static final BlockId COARSE_DIRT = BlockId.minecraft("coarse_dirt");
    public static final BlockId NETHERRACK = BlockId.minecraft("netherrack");
    public static final BlockId TERRACOTTA = BlockId.minecraft("terracotta");
    public static final BlockId WHITE_TERRACOTTA = BlockId.minecraft("white_terracotta");
    public static final BlockId YELLOW_TERRACOTTA = BlockId.minecraft("yellow_terracotta");
    public static final BlockId ORANGE_TERRACOTTA = BlockId.minecraft("orange_terracotta");
    public static final BlockId RED_TERRACOTTA = BlockId.minecraft("red_terracotta");
    public static final BlockId SANDSTONE = BlockId.minecraft("sandstone");
    public static final BlockId BONE_BLOCK = BlockId.minecraft("bone_block");

    /** Surfaces that hydrothermal processes may replace (rock, soil, sediment). */
    public static final Set<BlockId> DEFAULT_ALTERABLE = Set.of(
            BlockId.minecraft("stone"),
            BlockId.minecraft("andesite"),
            BlockId.minecraft("diorite"),
            BlockId.minecraft("granite"),
            BlockId.minecraft("tuff"),
            BlockId.minecraft("basalt"),
            BlockId.minecraft("smooth_basalt"),
            BlockId.minecraft("blackstone"),
            BlockId.minecraft("deepslate"),
            BlockId.minecraft("cobblestone"),
            BlockId.minecraft("cobbled_deepslate"),
            BlockId.minecraft("gravel"),
            BlockId.minecraft("dirt"),
            BlockId.minecraft("coarse_dirt"),
            BlockId.minecraft("grass_block"),
            BlockId.minecraft("podzol"),
            BlockId.minecraft("sand"),
            BlockId.minecraft("red_sand"),
            BlockId.minecraft("sandstone"),
            BlockId.minecraft("netherrack"));

    private GeothermalBlocks() {}

    /** Installs the default geothermal fallback chains on {@code palette}. */
    public static BlockPalette installFallbacks(BlockPalette palette) {
        return palette
                .fallback(SULFUR, YELLOW_TERRACOTTA, SANDSTONE)
                .compatibleFallback(SULFUR_SPIKE, POINTED_DRIPSTONE)
                .fallback(CINNABAR, RED_TERRACOTTA, TERRACOTTA)
                .fallback(CALCITE, DIORITE, BONE_BLOCK)
                .fallback(MUD, CLAY, COARSE_DIRT)
                .fallback(WHITE_TERRACOTTA, TERRACOTTA, CLAY)
                .fallback(YELLOW_TERRACOTTA, TERRACOTTA)
                .fallback(ORANGE_TERRACOTTA, TERRACOTTA)
                .fallback(RED_TERRACOTTA, TERRACOTTA)
                .fallback(MAGMA_BLOCK, NETHERRACK);
    }

    /** Default palette for a host supporting exactly {@code supported}. */
    public static BlockPalette defaultPalette(Set<BlockId> supported) {
        return installFallbacks(BlockPalette.supporting(supported));
    }
}
