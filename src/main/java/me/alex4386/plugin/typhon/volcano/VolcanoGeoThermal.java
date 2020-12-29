package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonPlugin;
import org.bukkit.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.PluginManager;

public class VolcanoGeoThermal implements Listener {
    public Volcano volcano;
    public boolean enable = true;
    public int scheduleID = -1;
    public int geoThermalUpdateRate = 200;

    public boolean registeredEvent = false;

    public VolcanoGeoThermal(Volcano volcano) {
        this.volcano = volcano;

        this.registerEvent();
        this.registerTask();
    }

    public void registerEvent() {
        if (!registeredEvent) {
            PluginManager pm = Bukkit.getPluginManager();
            pm.registerEvents(this, TyphonPlugin.plugin);
            registeredEvent = true;
        }
    }

    public void unregisterEvent() {
        if (registeredEvent) {
            BlockFromToEvent.getHandlerList().unregisterAll(this);
            registeredEvent = false;
        }
    }

    public void registerTask() {
        if (scheduleID == -1) {
            scheduleID = Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(
                    TyphonPlugin.plugin,
                    (Runnable) () -> {
                        if (enable) {
                            // something to do~~
                        }
                    }, 0, geoThermalUpdateRate
            );
        }
    }

    public void unregisterTask() {
        if (scheduleID != -1) {
            Bukkit.getScheduler().cancelTask(scheduleID);
            scheduleID = -1;
        }
    }

    public void initialize() {
        this.registerEvent();
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterEvent();
        this.unregisterTask();
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        if (enable && volcano.manager.getHeatValue(e.getPlayer().getLocation()) >= 1 - volcano.status.scaleFactor) {
            switch (e.getItemDrop().getItemStack().getType()) {
                case PORKCHOP:
                    e.getItemDrop().getItemStack().setType(Material.COOKED_PORKCHOP);
                    break;
                case BEEF:
                    e.getItemDrop().getItemStack().setType(Material.COOKED_BEEF);
                    break;
                case RABBIT:
                    e.getItemDrop().getItemStack().setType(Material.COOKED_RABBIT);
                    break;
                case CHICKEN:
                    e.getItemDrop().getItemStack().setType(Material.COOKED_CHICKEN);
                    break;
                case SALMON:
                    e.getItemDrop().getItemStack().setType(Material.COOKED_SALMON);
                    break;
                case LEGACY_RAW_FISH:
                    e.getItemDrop().getItemStack().setType(Material.LEGACY_COOKED_FISH);
                    break;
                case MUTTON:
                    e.getItemDrop().getItemStack().setType(Material.COOKED_MUTTON);
                    break;
                default:
            }
        }
    }
}
