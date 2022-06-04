package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.apache.logging.log4j.core.Version;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.PluginManager;

import java.util.List;
import java.util.Random;

public class VolcanoGeoThermal implements Listener {
    public Volcano volcano;
    public boolean enable = true;
    public int scheduleID = -1;
    public int geoThermalUpdateRate = 10;

    public boolean registeredEvent = false;

    private long lastLavaTime = -1;

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
            PlayerDropItemEvent.getHandlerList().unregisterAll(this);
            PlayerBucketEmptyEvent.getHandlerList().unregisterAll(this);
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
                            for (VolcanoVent vent : volcano.manager.getVents()) {
                                if (vent.enabled) {

                                    int geothermalRange = Math.max(50, (int) (vent.craterRadius * 2.5));

                                    for (int i = 0; i < vent.status.getScaleFactor() * Math.pow(geothermalRange / 50, 1 + (1.2 * vent.status.getScaleFactor())); i++) {
                                        Block block;

                                        if (Math.random() < 0.125 * ((double) vent.craterRadius / 20)) {
                                            block = TyphonUtils.getHighestRocklikes(
                                                    TyphonUtils.getRandomBlockInRange(
                                                            vent.location.getBlock(),
                                                            (int) Math.floor(vent.craterRadius)
                                                    )
                                            );
                                        } else {
                                            block = TyphonUtils.getHighestRocklikes(
                                                    TyphonUtils.getRandomBlockInRange(
                                                            vent.location.getBlock(),
                                                            (int) Math.floor(vent.craterRadius),
                                                            (int) Math.floor(geothermalRange)
                                                    )
                                            );
                                        }

                                        if (shouldDoIt(vent, block.getLocation()) || (Math.random() < (0.7 * vent.status.getScaleFactor()) && geothermalRange == 50 && vent.longestFlowLength <= 50)) {

                                            final Location targetLoc = block.getLocation().add(0, 1, 0);
                                            TyphonUtils.createRisingSteam(targetLoc, 1, 5);

                                            Entity[] entities = block.getChunk().getEntities();
                                            for (Entity entity : entities) {
                                                double distance = entity.getLocation().distance(targetLoc);
                                                if (distance < 3 && entity.getMaxFireTicks() != 0) {
                                                    entity.setFireTicks((int) (120 * volcano.manager.getHeatValue(targetLoc) * (distance / 3) * vent.status.getScaleFactor()));
                                                }
                                            }

                                            volcano.metamorphism.evaporateBlock(block);
                                            for (int x = -1; x <= 1; x++) {
                                                for (int y = -1; y <= 1; y++) {
                                                    for (int z = -1; z <= 1; z++) {
                                                        volcano.metamorphism.evaporateBlock(block.getRelative(x, y, z));
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Block ventTopBlock = TyphonUtils.getHighestRocklikes(vent.location.getBlock());

                                    if (vent.status.getScaleFactor() >= 0.1) {
                                        int length = (int) (Math.random() * 0.1 * Math.pow(vent.craterRadius, 2) * vent.status.getScaleFactor());

                                        for (int i = 0; i < length; i++) {
                                            Location location = TyphonUtils.getHighestLocation(TyphonUtils.getRandomBlockInRange(
                                                    vent.location.getBlock(),
                                                    (int) Math.floor(vent.craterRadius - 3) + (int) ((Math.random() * 6) - 3)
                                            ).getLocation());

                                            if (Math.random() < vent.status.getScaleFactor() && vent.getHeatValue(location) > 0.999) {
                                                int count = Math.abs((int) (volcano.manager.getHeatValue(location) - 0.999) * 1000) * 20;

                                                Entity[] entities = location.getChunk().getEntities();
                                                for (Entity entity : entities) {
                                                    double distance = entity.getLocation().distance(location);
                                                    if (distance < 5 && entity.getMaxFireTicks() != 0) {
                                                        entity.setFireTicks((int) (100 * volcano.manager.getHeatValue(location) * (distance / 5) * vent.status.getScaleFactor()));
                                                    }
                                                }
                                            }
                                        }

                                        if (lastLavaTime < 0 || lastLavaTime < System.currentTimeMillis() - 2000) {
                                            lastLavaTime = System.currentTimeMillis();

                                            vent.location.getWorld().playSound(
                                                    ventTopBlock.getLocation(),
                                                    Sound.BLOCK_LAVA_POP,
                                                    2f,
                                                    1f
                                            );
                                            vent.location.getWorld().playSound(
                                                    ventTopBlock.getLocation(),
                                                    Sound.BLOCK_LAVA_AMBIENT,
                                                    2f,
                                                    1f
                                            );
                                        }
                                    }
                                }
                            }
                        }
                    }, 0, Math.min((long) ((geoThermalUpdateRate / 20.0) * volcano.updateRate), 1)
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
        this.volcano.logger.log(VolcanoLogClass.GEOTHERMAL, "Intializing Volcano Geothermal...");
        this.registerEvent();
        this.registerTask();
    }

    public void shutdown() {
        this.volcano.logger.log(VolcanoLogClass.GEOTHERMAL, "Shutting down Volcano Geothermal...");
        this.unregisterEvent();
        this.unregisterTask();
    }

    public boolean shouldDoIt(VolcanoVent vent, Location location) {
        Random random = new Random();
        return enable && volcano.manager.getHeatValue(location) >= 1 - vent.status.getScaleFactor() && random.nextDouble() < vent.status.getScaleFactor();
    }

    @EventHandler
    public void onBlockForm(BlockFormEvent e) {
        List<VolcanoVent> vents = volcano.manager.getVents();

        for (VolcanoVent vent : vents) {
            if (shouldDoIt(vent, e.getBlock().getLocation())) {
                if (e.getBlock().getType().name().toLowerCase().contains("snow")) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void playerWaterFlow(PlayerBucketEmptyEvent event) {
        Material bucket = event.getBucket();
        Block clickedBlock = event.getBlockClicked();
        Block targetBlock = clickedBlock.getRelative(event.getBlockFace());
        Location loc = targetBlock.getLocation();

        if (volcano.manager.getHeatValue(loc) > 0.7) {
            if (bucket == Material.WATER_BUCKET || bucket == Material.WATER || bucket == Material.POWDER_SNOW_BUCKET || bucket == Material.POWDER_SNOW) {
                TyphonUtils.createRisingSteam(loc, 1, 5);

                event.getPlayer().sendMessage(ChatColor.RED+"Heat of the volcano evaporated your water!");

                event.setCancelled(true);
                if (event.getPlayer().getInventory().getItemInMainHand().getType() == bucket) {
                    event.getPlayer().getInventory().getItemInMainHand().setType(Material.BUCKET);
                }
            }
        }

        // temp lava flow handler
        if (bucket == Material.LAVA_BUCKET) {
            VolcanoVent vent = volcano.manager.getNearestVent(targetBlock);
            if (vent != null) {
                vent.lavaFlow.flowLava(targetBlock);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        List<VolcanoVent> vents = volcano.manager.getVents();

        for (VolcanoVent vent : vents) {
            if (shouldDoIt(vent, e.getPlayer().getLocation())) {
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
                    case COD:
                        e.getItemDrop().getItemStack().setType(Material.COOKED_COD);
                        break;
                    case KELP:
                        e.getItemDrop().getItemStack().setType(Material.DRIED_KELP);
                        break;
                    case POTATO:
                        e.getItemDrop().getItemStack().setType(Material.BAKED_POTATO);
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
}
