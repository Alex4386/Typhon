package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VolcanoBombListener implements Listener {
    public static Map<Block, VolcanoVent> lavaSplashExplosions = new HashMap<>();

    public static int bombTrackingScheduleId = -1;
    public static int updatesPerSeconds = 4;

    public static boolean registeredEvent = false;

    public void registerTask() {
        if (bombTrackingScheduleId < 0) {
            bombTrackingScheduleId = Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, (Runnable) () -> {
                for (Map.Entry<String, Volcano> entry: TyphonPlugin.listVolcanoes.entrySet()) {
                    Volcano volcano = entry.getValue();

                    List<VolcanoVent> vents = volcano.manager.getVents();
                    for (VolcanoVent vent: vents) {
                        vent.bombs.trackAll();
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
                        for (VolcanoVent vent : volcano.manager.getVents()) {
                            VolcanoBomb bomb = vent.bombs.bombMap.get(fallingBlock);
                            if (bomb != null) {
                                bomb.startTrail();
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        Location explosionTriggered = event.getBlock().getLocation();
        Block triggerBlock = explosionTriggered.getBlock();
        VolcanoVent vent = lavaSplashExplosions.get(triggerBlock);

        if (vent != null) {
            for (Block block : event.blockList()) {
                float x = (float)(block.getX() - explosionTriggered.getX());
                float y = (float)(block.getY() - explosionTriggered.getY()) + 1;
                float z = (float)(block.getZ() - explosionTriggered.getZ());

                x *= (Math.random() - 0.5) * 2;
                y *= (0.5 + (Math.random() * 0.8));
                z *= (Math.random() - 0.5) * 2;

                FallingBlock fallingBlock;
                boolean isBomb = false;

                if (Math.random() < ((vent.lavaFlow.settings.silicateLevel - 0.2) / 5) ) {
                    isBomb = true;
                }

                if (isBomb) {
                    block.setType(Material.MAGMA_BLOCK);
                }

                fallingBlock = block.getWorld().spawnFallingBlock(block.getLocation(), block.getBlockData());
                fallingBlock.setDropItem(false);
                fallingBlock.setVelocity(new Vector(x,y,z));

                if (isBomb) {
                    vent.bombs.bombMap.put(fallingBlock, new VolcanoBomb(vent, block.getLocation(), fallingBlock, 4.0f, 1, 1));
                }

                block.setType(Material.AIR);
            }

            vent.record.addEjectaVolume(event.blockList().size());
            lavaSplashExplosions.remove(explosionTriggered.getBlock());
        }
    }


}
