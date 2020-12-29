package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.PluginManager;

import java.util.List;
import java.util.Map;

public class VolcanoBombListener implements Listener {

    public static int bombTrackingScheduleId = -1;
    public static int updatesPerSeconds = 4;

    public static boolean registeredEvent = false;

    public void registerTask() {
        if (bombTrackingScheduleId < 0) {
            bombTrackingScheduleId = Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, (Runnable) () -> {
                for (Map.Entry<String, Volcano> entry: TyphonPlugin.listVolcanoes.entrySet()) {
                    Volcano volcano = entry.getValue();

                    List<VolcanoCrater> craters = volcano.manager.getCraters();
                    for (VolcanoCrater crater:craters) {
                        crater.bombs.trackAll();
                    }
                }
            }, 0L, (long) TyphonPlugin.minecraftTicksPerSeconds / updatesPerSeconds);
        }
    }

    public void unregisterTask() {
        if (bombTrackingScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(bombTrackingScheduleId);
            bombTrackingScheduleId = -1;
        }
    }

    public void registerEvent() {
        if (!registeredEvent) {
            PluginManager pm = Bukkit.getPluginManager();
            pm.registerEvents(this, TyphonPlugin.plugin);
        }
    }

    public void unregisterEvent() {
        if (registeredEvent) {
            PlayerMoveEvent.getHandlerList().unregisterAll(this);
        }
    }

    public void initialize() {
        this.registerTask();
        this.registerEvent();
    }

    public void shutdown() {
        this.unregisterTask();
        this.unregisterEvent();
    }


    public static boolean groundChecker(Location location, int offset) {
        return (location.getBlockY() - TyphonUtils.getHighestOceanFloor(location).getY() <= offset);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        for (Entity entity: player.getLocation().getChunk().getEntities()) {
            if (entity instanceof FallingBlock) {
                FallingBlock fallingBlock = (FallingBlock) entity;

                for (Map.Entry<String, Volcano> entry: TyphonPlugin.listVolcanoes.entrySet()) {
                    Volcano volcano = entry.getValue();

                    if (volcano.location.getWorld().equals(player.getWorld())) {
                        for (VolcanoCrater crater : volcano.manager.getCraters()) {
                            VolcanoBomb bomb = crater.bombs.bombMap.get(fallingBlock);
                            if (bomb != null) {
                                bomb.startTrail();
                            }
                        }
                    }
                }
            }
        }
    }

}
