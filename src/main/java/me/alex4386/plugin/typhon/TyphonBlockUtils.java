package me.alex4386.plugin.typhon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class TyphonBlockUtils {
    /**
     * Sets the type of a block with WorldGuard region protection check
     * 
     * @param block The block to modify
     * @param material The material to set
     * @return true if the block was modified, false if blocked by WorldGuard
     */
    public static boolean setBlockType(Block block, Material material) {
        if (TyphonPlugin.worldGuard != null) {
            if (!TyphonPlugin.worldGuard.isAllowedAt(block.getLocation())) {
                return false;
            }
        }
        block.setType(material);
        return true;
    }

    /**
     * Sets the type of a block with WorldGuard region protection check and player context
     * 
     * @param block The block to modify
     * @param material The material to set
     * @param player The player context for permission checking
     * @return true if the block was modified, false if blocked by WorldGuard
     */
    public static boolean setBlockType(Block block, Material material, Player player) {
        if (TyphonPlugin.worldGuard != null) {
            if (!TyphonPlugin.worldGuard.isAllowedAt(player, block.getLocation())) {
                return false;
            }
        }
        block.setType(material);
        return true;
    }

    /**
     * Checks if a block can be modified at the given location
     * 
     * @param location The location to check
     * @return true if the block can be modified, false if blocked by WorldGuard
     */
    public static boolean canModifyBlock(Location location) {
        if (TyphonPlugin.worldGuard != null) {
            return TyphonPlugin.worldGuard.isAllowedAt(location);
        }
        return true;
    }

    /**
     * Checks if a block can be modified at the given location with player context
     * 
     * @param location The location to check
     * @param player The player context for permission checking
     * @return true if the block can be modified, false if blocked by WorldGuard
     */
    public static boolean canModifyBlock(Location location, Player player) {
        if (TyphonPlugin.worldGuard != null) {
            return TyphonPlugin.worldGuard.isAllowedAt(player, location);
        }
        return true;
    }
}