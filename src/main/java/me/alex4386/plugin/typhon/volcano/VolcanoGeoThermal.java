package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonNMSUtils;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
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
                            for (VolcanoCrater crater : volcano.manager.getCraters()) {
                                if (crater.enabled) {

                                    int geothermalRange = (crater.longestFlowLength <= 50) ? 50 : (int) crater.longestFlowLength + 100;
                                    for (int i = 0; i < crater.status.getScaleFactor() * Math.pow(geothermalRange / 50, 1 + (1.2 * crater.status.getScaleFactor())); i++) {
                                        Block block;

                                        if (Math.random() < 0.125 * ((double) crater.craterRadius / 20)) {
                                            block = TyphonUtils.getHighestRocklikes(
                                                    TyphonUtils.getRandomBlockInRange(
                                                            crater.location.getBlock(),
                                                            (int) Math.floor(crater.craterRadius)
                                                    )
                                            );
                                        } else {
                                            block = TyphonUtils.getHighestRocklikes(
                                                    TyphonUtils.getRandomBlockInRange(
                                                            crater.location.getBlock(),
                                                            (int) Math.floor(crater.craterRadius),
                                                            (int) Math.floor(geothermalRange)
                                                    )
                                            );
                                        }

                                        if (shouldDoIt(crater, block.getLocation()) || (Math.random() < (0.7 * crater.status.getScaleFactor()) && geothermalRange == 50 && crater.longestFlowLength <= 50)) {

                                            final Location targetLoc = block.getLocation().add(0, 1, 0);
                                            TyphonUtils.createRisingSteam(targetLoc, 1, 5);

                                            Entity[] entities = block.getChunk().getEntities();
                                            for (Entity entity : entities) {
                                                double distance = entity.getLocation().distance(targetLoc);
                                                if (distance < 3 && entity.getMaxFireTicks() != 0) {
                                                    entity.setFireTicks((int) (120 * volcano.manager.getHeatValue(targetLoc) * (distance / 3) * crater.status.getScaleFactor()));
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

                                    Block craterTopBlock = TyphonUtils.getHighestRocklikes(crater.location.getBlock());

                                    if (crater.status.getScaleFactor() >= 0.1) {
                                        int length = (int) (Math.random() * 0.1 * Math.pow(crater.craterRadius, 2) * crater.status.getScaleFactor());

                                        for (int i = 0; i < length; i++) {
                                            Location location = TyphonUtils.getHighestLocation(TyphonUtils.getRandomBlockInRange(
                                                    crater.location.getBlock(),
                                                    (int) Math.floor(crater.craterRadius - 3) + (int) ((Math.random() * 6) - 3)
                                            ).getLocation());

                                            if (Math.random() < crater.status.getScaleFactor() && crater.getHeatValue(location) > 0.999) {
                                                int count = Math.abs((int) (volcano.manager.getHeatValue(location) - 0.999) * 1000) * 20;
                                                crater.location.getWorld().spawnParticle(
                                                        Particle.LAVA,
                                                        location,
                                                        count);

                                                Entity[] entities = location.getChunk().getEntities();
                                                for (Entity entity : entities) {
                                                    double distance = entity.getLocation().distance(location);
                                                    if (distance < 5 && entity.getMaxFireTicks() != 0) {
                                                        entity.setFireTicks((int) (100 * volcano.manager.getHeatValue(location) * (distance / 5) * crater.status.getScaleFactor()));
                                                    }
                                                }
                                            }
                                        }

                                        if (lastLavaTime < 0 || lastLavaTime < System.currentTimeMillis() - 2000) {
                                            lastLavaTime = System.currentTimeMillis();

                                            crater.location.getWorld().playSound(
                                                    craterTopBlock.getLocation(),
                                                    Sound.BLOCK_LAVA_POP,
                                                    2f,
                                                    1f
                                            );
                                            crater.location.getWorld().playSound(
                                                    craterTopBlock.getLocation(),
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
        this.registerEvent();
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterEvent();
        this.unregisterTask();
    }

    public boolean shouldDoIt(VolcanoCrater crater, Location location) {
        Random random = new Random();
        return enable && volcano.manager.getHeatValue(location) >= 1 - crater.status.getScaleFactor() && random.nextDouble() < crater.status.getScaleFactor();
    }

    @EventHandler
    public void onBlockForm(BlockFormEvent e) {
        List<VolcanoCrater> craters = volcano.manager.getCraters();

        for (VolcanoCrater crater : craters) {
            if (shouldDoIt(crater, e.getBlock().getLocation())) {
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
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        List<VolcanoCrater> craters = volcano.manager.getCraters();

        for (VolcanoCrater crater : craters) {
            if (shouldDoIt(crater, e.getPlayer().getLocation())) {
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
