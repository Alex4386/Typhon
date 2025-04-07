package me.alex4386.plugin.typhon;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class TyphonWorldGuardUtils {
    private static TyphonWorldGuardUtils instance;
    private final Plugin plugin;
    private boolean worldGuardEnabled = false;
    private boolean respectRegions = true;
    private StateFlag BUILD_FLAG;
    private WorldGuardPlugin worldGuardPlugin;

    private TyphonWorldGuardUtils(Plugin plugin) {
        this.plugin = plugin;
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            BUILD_FLAG = (StateFlag) WorldGuard.getInstance().getFlagRegistry().get("build");
            worldGuardPlugin = WorldGuardPlugin.inst();
            worldGuardEnabled = worldGuardPlugin != null && plugin.getConfig().getBoolean("worldGuard.enable", true);
            respectRegions = plugin.getConfig().getBoolean("worldGuard.respectRegions", true);
            if (worldGuardEnabled) {
                plugin.getLogger().info("WorldGuard support enabled" + (respectRegions ? " (respecting regions)" : " (not respecting regions)"));
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().info("WorldGuard not found - region protection disabled");
        }
    }

    public static TyphonWorldGuardUtils getInstance(Plugin plugin) {
        if (instance == null) {
            instance = new TyphonWorldGuardUtils(plugin);
        }
        return instance;
    }

    public boolean isAllowedAt(Location location) {
        if (!worldGuardEnabled || !respectRegions) return true;

        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(location));
        
        // If no regions or build flag allows, allow action
        return set.size() == 0 || (BUILD_FLAG != null && set.testState(null, BUILD_FLAG));
    }

    public boolean isAllowedAt(Player player, Location location) {
        if (!worldGuardEnabled || !respectRegions || worldGuardPlugin == null) return true;
        if (player.hasPermission(plugin.getConfig().getString("worldGuard.bypassPermission", "typhon.worldguard.bypass"))) return true;

        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(location));
        
        // If no regions or player has build permission in region, allow action
        return set.size() == 0 || (BUILD_FLAG != null && set.testState(worldGuardPlugin.wrapPlayer(player), BUILD_FLAG));
    }

    public boolean isWorldGuardEnabled() {
        return worldGuardEnabled;
    }
}