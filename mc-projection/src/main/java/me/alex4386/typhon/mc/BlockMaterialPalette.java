package me.alex4386.typhon.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;

/**
 * Two-way mapping between block ids (host worlds, Minecraft) and world-model materials.
 *
 * <ul>
 *   <li>{@link #material(BlockId)}: what a host block is made of, used when a host terrain snapshot
 *       or a block edit is imported into the stacks.
 *   <li>{@link #block(Material)}: the block that shows a material, for rendering stacks back into a
 *       block world.
 * </ul>
 */
public final class BlockMaterialPalette {
    private final Map<BlockId, Material> toMaterial = new HashMap<>();
    private final Map<Short, BlockId> toBlock = new HashMap<>();
    private final Material fallback;

    public BlockMaterialPalette(Material fallback) {
        this.fallback = Objects.requireNonNull(fallback);
    }

    /** Vanilla Minecraft blocks mapped onto the closest material; anything else is andesite. */
    public static BlockMaterialPalette minecraft() {
        BlockMaterialPalette p = new BlockMaterialPalette(MaterialTable.ANDESITE);
        p.both("basalt", MaterialTable.BASALT);
        p.map("polished_basalt", MaterialTable.BASALT);
        p.map("smooth_basalt", MaterialTable.BASALT);
        p.map("blackstone", MaterialTable.BASALT);
        p.map("magma_block", MaterialTable.BASALT);
        p.map("netherrack", MaterialTable.BASALT);
        p.both("andesite", MaterialTable.ANDESITE);
        p.map("stone", MaterialTable.ANDESITE);
        p.map("cobblestone", MaterialTable.ANDESITE);
        p.map("deepslate", MaterialTable.GABBRO);
        p.both("diorite", MaterialTable.DACITE);
        p.map("quartz_block", MaterialTable.RHYOLITE);
        p.block(MaterialTable.RHYOLITE, "calcite");
        p.both("obsidian", MaterialTable.OBSIDIAN);
        p.map("crying_obsidian", MaterialTable.OBSIDIAN);
        p.both("tuff", MaterialTable.TUFF);
        p.both("light_gray_concrete_powder", MaterialTable.ASH);
        p.map("gray_concrete_powder", MaterialTable.ASH);
        p.both("gravel", MaterialTable.SCORIA);
        p.block(MaterialTable.DEBRIS, "cobblestone");
        p.block(MaterialTable.PUMICE, "white_concrete_powder");
        p.map("white_concrete_powder", MaterialTable.PUMICE);
        p.both("mud", MaterialTable.LAHAR_DEPOSIT);
        p.map("packed_mud", MaterialTable.LAHAR_DEPOSIT);
        p.block(MaterialTable.HYALOCLASTITE, "smooth_basalt");
        p.both("granite", MaterialTable.GRANITE);
        p.block(MaterialTable.GABBRO, "deepslate");
        p.both("sandstone", MaterialTable.SEDIMENT);
        p.map("sand", MaterialTable.SEDIMENT);
        p.map("red_sand", MaterialTable.SEDIMENT);
        p.map("calcite", MaterialTable.SEDIMENT);
        p.both("dirt", MaterialTable.SOIL);
        p.map("grass_block", MaterialTable.SOIL);
        p.map("coarse_dirt", MaterialTable.SOIL);
        p.map("podzol", MaterialTable.SOIL);
        p.map("rooted_dirt", MaterialTable.SOIL);
        p.both("clay", MaterialTable.CLAY);
        p.map("terracotta", MaterialTable.CLAY);
        p.both("sulfur", MaterialTable.SULFUR);
        p.map("yellow_terracotta", MaterialTable.SULFUR);
        p.both("ice", MaterialTable.ICE);
        p.map("packed_ice", MaterialTable.ICE);
        p.map("blue_ice", MaterialTable.ICE);
        p.both("water", MaterialTable.WATER);
        p.both("air", MaterialTable.AIR);
        p.block(MaterialTable.VOID, "cave_air");
        p.map("cave_air", MaterialTable.VOID);
        return p;
    }

    private void both(String path, Material material) {
        map(path, material);
        block(material, path);
    }

    /** Host block → material. */
    public BlockMaterialPalette map(String path, Material material) {
        toMaterial.put(BlockId.parse(path), material);
        return this;
    }

    /** Material → host block. */
    public BlockMaterialPalette block(Material material, String path) {
        toBlock.put(material.id(), BlockId.parse(path));
        return this;
    }

    public Material material(BlockId block) {
        return toMaterial.getOrDefault(block, fallback);
    }

    /** Whether the block maps to a material explicitly (rather than the fallback). */
    public boolean knows(BlockId block) {
        return toMaterial.containsKey(block);
    }

    public BlockId block(Material material) {
        BlockId id = toBlock.get(material.id());
        return id != null ? id : toBlock.getOrDefault(fallback.id(), BlockId.minecraft("stone"));
    }
}
