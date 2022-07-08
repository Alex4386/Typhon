package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.PluginManager;

import java.util.Collection;
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

  public void runCraterGeothermal(VolcanoVent vent) {
    Block block = this.getBlockToRunCraterCycle(vent);

    final Location targetLoc = block
      .getLocation()
      .add(0, 1, 0);
      
    TyphonUtils.createRisingSteam(
        targetLoc,
        1,
        5);

    this.burnNearbyEntities(targetLoc, 3);
    vent.volcano.metamorphism.evaporateBlock(block);
  }

  public void runGeoThermalCycle(VolcanoVent vent) {
    if (vent.enabled) {
      this.runCraterGeoThermalCycle(vent);
    }
  }

  public void runCraterGeoThermalCycle(VolcanoVent vent) {
    int geothermalRange = getCraterGeoThermalRadius(vent);
    int cycleCount = (int) Math.min(vent.status.getScaleFactor() * Math.pow(
      geothermalRange / 50,
      1 + (1.2 * vent.status .getScaleFactor())
    ), (int) (geothermalRange * geothermalRange * Math.PI));

    for (int i = 0; i < cycleCount; i++) {
      this.runCraterGeothermal(vent);
    }  
  }

  public Block getBlockToRunCraterCycle(VolcanoVent vent) {
    return getBlockToRunCraterCycle(vent, getCraterGeoThermalRadius(vent));
  }

  public Block getBlockToRunCraterCycle(VolcanoVent vent, double geoThermalRadius) {
    Block block = vent.location.getBlock();

    if (Math.random() < 0.125 * ((double) vent.craterRadius / 20)) {
      block = TyphonUtils
        .getHighestRocklikes(vent.getCoreBlock());
    } else {
      block = TyphonUtils
          .getHighestRocklikes(
              TyphonUtils
                  .getRandomBlockInRange(
                      vent.getCoreBlock(),
                      (int) Math
                          .floor(
                              vent.craterRadius),
                      (int) Math
                          .floor(
                              geoThermalRadius)));
    }

    return block;
  }

  
  public int getCraterGeoThermalRadius(VolcanoVent vent) {
    int geothermalRange = Math.max(50, (int) (vent.craterRadius * 2.5));

    return geothermalRange;
  }

  public void playLavaBubbling(Location location) {
    location
      .getWorld()
      .playSound(
        location,
        Sound.BLOCK_LAVA_POP,
        2f,
        1f);

    location
      .getWorld()
      .playSound(
        location,
        Sound.BLOCK_LAVA_AMBIENT,
        2f,
        1f);
  }

  public void burnNearbyEntities(Location location, double range) {
    Collection<Entity> entities = location.getWorld().getNearbyEntities(location, range, range, range);
    
    for (Entity entity : entities) {
      double distance = entity.getLocation().distance(location);
      double fireTicks = (120 * volcano.manager.getHeatValue(location)) * (distance / range);
      
      if (distance < range && entity.getMaxFireTicks() != 0 ) {
        entity.setFireTicks((int) fireTicks);
      }
    }
  }

  public void registerTask() {
    if (scheduleID == -1) {
      scheduleID = Bukkit.getServer()
          .getScheduler()
          .scheduleSyncRepeatingTask(
              TyphonPlugin.plugin,
              (Runnable) () -> {
                if (enable) {
                  for (VolcanoVent vent : volcano.manager.getVents()) {
                    this.runGeoThermalCycle(vent);
                  }
                }
              },
              0,
              Math.min(
                  (long) ((geoThermalUpdateRate / 20.0)
                      * volcano.updateRate),
                  1));
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
    return enable
        && volcano.manager.getHeatValue(location) >= 1 - vent.status.getScaleFactor()
        && random.nextDouble() < vent.status.getScaleFactor();
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

    if (volcano.manager.getHeatValue(loc) > 0.8) {
      if (bucket == Material.WATER_BUCKET
          || bucket == Material.WATER
          || bucket == Material.POWDER_SNOW_BUCKET
          || bucket == Material.POWDER_SNOW) {
        TyphonUtils.createRisingSteam(loc, 1, 5);

        event.getPlayer()
            .sendMessage(ChatColor.RED
                + "Heat of the volcano evaporated your water!");

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
