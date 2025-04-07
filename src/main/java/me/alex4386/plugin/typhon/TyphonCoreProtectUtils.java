package me.alex4386.plugin.typhon;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class TyphonCoreProtectUtils {
    private static TyphonCoreProtectUtils instance;
    private final Plugin plugin;
    private CoreProtectAPI coreProtect;
    private boolean coreProtectEnabled = false;

    private TyphonCoreProtectUtils(Plugin plugin) {
        this.plugin = plugin;
        try {
            Plugin coreProtectPlugin = plugin.getServer().getPluginManager().getPlugin("CoreProtect");
            if (coreProtectPlugin instanceof CoreProtect && coreProtectPlugin.isEnabled()) {
                CoreProtectAPI coreProtectAPI = ((CoreProtect) coreProtectPlugin).getAPI();
                if (coreProtectAPI.isEnabled() && coreProtectAPI.APIVersion() >= 9) {
                    this.coreProtect = coreProtectAPI;
                    this.coreProtectEnabled = true;
                    plugin.getLogger().info("CoreProtect support enabled");
                } else {
                    plugin.getLogger().warning("CoreProtect API version is not compatible");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().info("CoreProtect not found - block logging disabled");
        }
    }

    public static TyphonCoreProtectUtils getInstance(Plugin plugin) {
        if (instance == null) {
            instance = new TyphonCoreProtectUtils(plugin);
        }
        return instance;
    }

    private static String getTyphonPluginName(Player player) {
        if (player == null) return "#typhon";
        return player.getName();
    }

    /**
     * Log a block change in CoreProtect
     * 
     * @param block The block that was modified
     * @param oldMaterial The original material of the block
     * @param newMaterial The new material of the block
     * @return true if the block change was logged successfully
     */
    public boolean logTyphonBlockChange(Block block, Material oldMaterial, Material newMaterial) {
      return this.logBlockChange(null, block, oldMaterial, newMaterial);
    }

    public boolean logBlockChange(Player player, Block block, Material oldMaterial, Material newMaterial) {
        if (!coreProtectEnabled) return false;

        String name = getTyphonPluginName(player);

        // Log as #typhon for volcanic activity
        coreProtect.logRemoval(name, block.getLocation(), oldMaterial, block.getBlockData());
        coreProtect.logPlacement(name, block.getLocation(), newMaterial, block.getBlockData());
        return true;
    }

    /**
     * Log a block break in CoreProtect
     * 
     * @param block The block that was broken
     * @return true if the block break was logged successfully
     */
    public boolean logTyphonBlockBreak(Block block) {
        return this.logBlockBreak(null, block);
    }

    public boolean logBlockBreak(Player player, Block block) {
        if (!coreProtectEnabled) return false;

        String name = getTyphonPluginName(player);

        BlockState state = block.getState();
        coreProtect.logRemoval(name, block.getLocation(), state.getType(), state.getBlockData());
        return true;
    }

    /**
     * Log a block place in CoreProtect
     * 
     * @param block The block that was placed
     * @return true if the block place was logged successfully
     */
    public boolean logTyphonBlockPlace(Block block) {
        return this.logBlockPlace(null, block);
    }

    public boolean logBlockPlace(Player player, Block block) {
        if (!coreProtectEnabled) return false;

        String name = getTyphonPluginName(player);

        BlockState state = block.getState();
        coreProtect.logPlacement(name, block.getLocation(), state.getType(), state.getBlockData());
        return true;
    }

    /**
     * Lookup block history at a location
     * 
     * @param location The location to check
     * @param time Time in seconds to look back
     * @return The block history data
     */
    public String[] lookupBlockHistory(Location location, int time) {
        if (!coreProtectEnabled) return new String[0];

        java.util.List<String[]> lookup = coreProtect.blockLookup(location.getBlock(), time);
        if (lookup != null) {
            return lookup.stream()
                .map(record -> String.join(", ", record))
                .toArray(String[]::new);
        }
        return new String[0];
    }

    public boolean isCoreProtectEnabled() {
        return coreProtectEnabled;
    }
}