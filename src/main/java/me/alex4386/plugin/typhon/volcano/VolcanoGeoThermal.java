package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;
import java.util.List;
import java.util.Random;

public class VolcanoGeoThermal implements Listener {
  public Volcano volcano;
  public boolean enable = true;
  public int scheduleID = -1;
  public int geoThermalUpdateRate = 10;

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
      PlayerDropItemEvent.getHandlerList().unregisterAll(this);
      PlayerBucketEmptyEvent.getHandlerList().unregisterAll(this);
      registeredEvent = false;
    }
  }

  public void runCraterGeothermal(VolcanoVent vent) {
    Block block = this.getBlockToRunCraterCycle(vent);

    double scaleFactor = vent.getStatus().getScaleFactor();

    final Location targetLoc = block
      .getLocation()
      .add(0, 1, 0);
      
    boolean letOffSteam = false;
    boolean runPoison = false;

    if (scaleFactor >= 0.1) {
      this.burnNearbyEntities(targetLoc, 3);
      this.playLavaBubbling(targetLoc);

      letOffSteam = true;
      runPoison = Math.random() < scaleFactor;
    } else if (scaleFactor >= 0.04) {
      this.burnNearbyEntities(targetLoc, 1);
    
      letOffSteam = (Math.random() < 0.5);
      runPoison = (Math.random() < scaleFactor);
    } else if (vent.getStatus() == VolcanoVentStatus.EXTINCT) {
      return;
    }

    if (letOffSteam) {
      TyphonUtils.createRisingSteam(
        targetLoc,
        0,
        1
      );

      if (runPoison) {
        this.volcanicGasDegassing(block);
      }
    }

    if (Math.random() < scaleFactor) {
      if (letOffSteam) {
        if (runPoison || Math.random() < scaleFactor) {
          vent.volcano.metamorphism.evaporateBlock(block);
        }
      }

      Block aboveBlock = block.getRelative(BlockFace.UP);
      if (!VolcanoComposition.isVolcanicRock(block.getType())) {
        vent.volcano.metamorphism.metamorphoseBlock(block);
      } else if (TyphonUtils.isMaterialTree(aboveBlock.getType())) {
        vent.volcano.metamorphism.removeTree(aboveBlock);
      }
    }
  }

  public void volcanicGasDegassing(Block block) {
    TyphonUtils.createRisingSteam(
        block.getLocation(),
        1,
        2);

    // Oops~ 
    if (Math.random() < 0.3) {
      this.runVolcanicGas(block.getLocation());
    }
  }

  public void runGeoThermalCycle(VolcanoVent vent) {
    if (vent.enabled) {
      this.runCraterGeoThermalCycle(vent);
      this.runVolcanoGeoThermalCycle(vent);
    }
  }

  public double getCraterCycleCount(VolcanoVent vent) {
    double circumference;
    if (vent.getType() == VolcanoVentType.CRATER) {
      circumference = vent.craterRadius * Math.PI * 2;
    } else {
      circumference = vent.fissureLength;
    }

    double cyclesPerTick = (double) 20.0 / geoThermalUpdateRate;
    double maxCount = Math.min(Math.max(1, circumference / 50), 100 / cyclesPerTick);
    return maxCount;
  }

  public void runCraterGeoThermalCycle(VolcanoVent vent) {
    double thermalScale = Math.pow(vent.getStatus().getScaleFactor(), 1.5);
    double maxCount = this.getCraterCycleCount(vent);

    double cycleCount = thermalScale * maxCount * Math.random();

    for (int i = 0; i < cycleCount; i++) {
      this.runCraterGeothermal(vent);
    }  
  }

  public void runVolcanoGeoThermalCycle(VolcanoVent vent) {
    double volcanoRange = (Math.PI * Math.pow(vent.longestNormalLavaFlowLength, 2));

    double radius = 10;
    if (vent.getType() == VolcanoVentType.CRATER) {
      radius = vent.craterRadius;
    }

    volcanoRange -= (Math.PI * Math.pow(vent.craterRadius, 2));
    double craterRange = (Math.PI * Math.pow(radius, 2));
    double volcanoMultiplier = Math.max(Math.min(100, volcanoRange / craterRange), 1);

    double maxCount = this.getCraterCycleCount(vent) * volcanoMultiplier;
    double thermalScale = Math.pow(vent.getStatus().getScaleFactor(), 1.5);
    double cycleCount = thermalScale * maxCount * Math.random();

    cycleCount = (int) cycleCount;

    if (vent.lavaFlow.hasAnyLavaFlowing()) {
      int lavaFlowCount = (int) (cycleCount / 2);
      List<Block> targets = vent.lavaFlow.getRandomLavaBlocks(lavaFlowCount);
      for (Block target: targets) {
        Block actualTarget = TyphonUtils.getRandomBlockInRange(target, 1, Math.max(2, (int) (2 + (4 * vent.getHeatValue(target.getLocation())))));
        this.runVolcanoGeoThermal(vent, actualTarget, false);
      }
    }

    for (int i = 0; i < cycleCount; i++) {
      this.runVolcanoGeoThermal(vent, this.getVolcanoGeoThermalBlock(vent));
    }
  }

  public Block getBlockToRunCraterCycle(VolcanoVent vent) {
    return getBlockToRunCraterCycle(vent, getCraterGeoThermalRadius(vent));
  }

  public Block getBlockToRunCraterCycle(VolcanoVent vent, double geoThermalRadius) {
    Block block = vent.location.getBlock();

    int craterRadius = vent.craterRadius;
    double range = this.getCraterGeoThermalRadius(vent) - craterRadius;
    double offset = VolcanoMath.getZeroFocusedRandom() * range;

    block = TyphonUtils
      .getHighestRocklikes(
          TyphonUtils
              .getRandomBlockInRange(
                  vent.getCoreBlock(),
                  0,
                  (int) (vent.craterRadius + offset)
              )
      );
    return block;
  }

  
  public int getCraterGeoThermalRadius(VolcanoVent vent) {
    int geothermalRange = Math.min(100, (int) (vent.craterRadius * 2.5));

    return geothermalRange;
  }

  public Block getVolcanoGeoThermalBlock(VolcanoVent vent) {
    int radius = 0;
    if (vent.getType() == VolcanoVentType.CRATER) {
      radius = vent.craterRadius;
    }

    Block block;

    block =
          TyphonUtils
              .getRandomBlockInRange(
                  vent.getCoreBlock(),
                  radius,
                  (int) Math
                      .floor(
                        vent.longestNormalLavaFlowLength));

    return block;
  }

  public void runVolcanoGeoThermal(VolcanoVent vent, Block block) {
    this.runVolcanoGeoThermal(vent, block, true);
  }

  public void runVolcanoGeoThermal(VolcanoVent vent, Block block, boolean allowSteam) {
    Block targetBlock = TyphonUtils.getHighestRocklikes(block);
    Location targetLoc = targetBlock.getLocation().add(0,1,0);

    double scaleFactor = vent.getStatus().getScaleFactor();
    double heatValue = vent.getHeatValue(block.getLocation());
      
    boolean letOffSteam = false;
    boolean runPoison = false;

    if (scaleFactor >= 0.1) {
      letOffSteam = true;
      runPoison = Math.random() < scaleFactor;
    } else if (scaleFactor >= 0.04) {
      letOffSteam = (Math.random() < 0.5);
      runPoison = (Math.random() < scaleFactor);
    }

    if (Math.random() < heatValue) {
      if (letOffSteam && allowSteam) {
        double burnRange = 3 * scaleFactor * heatValue;

        TyphonUtils.createRisingSteam(targetLoc, 1, 3);
        if (burnRange > 0) {
          this.burnNearbyEntities(targetLoc, 3);
        }

        if (runPoison) {
          this.runVolcanicGas(targetLoc);
        }

        if (burnRange > 1) {
          vent.volcano.metamorphism.evaporateBlock(block);
          Block upperBlock = targetBlock.getRelative(BlockFace.UP);

          if (!VolcanoComposition.isVolcanicRock(targetBlock.getType())) {
            vent.volcano.metamorphism.metamorphoseBlock(targetBlock);
          } else if (TyphonUtils.isMaterialTree(upperBlock.getType())) {
            vent.volcano.metamorphism.removeTree(upperBlock);
          }
        }
      }
    }
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

    location
      .getWorld()
      .spawnParticle(
        Particle.LAVA,
        location.add(0,1,0),
        1
    );
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

  //
  public void runVolcanicGas(Location location) {
    VolcanoVent vent = volcano.manager.getNearestVent(location);
    if (vent == null) return;
/*
    double referenceH2sGasPpm = volcano.manager.getHeatValue(location) * (150 + (100 * Math.random()));

    double h2sGasPpm = referenceH2sGasPpm;
    double so2GasPpm = h2sGasPpm * vent.getStatus().getScaleFactor() / 0.1;
    
    double minimumH2s = 0.75;
    double minimumSo2 = 0.65;
    
    double concentrationAfterSpreading = 0.11;

    // dillution = 1 -> 9 (1/9 equivalent to 0.11)
    double h2sRange = Math.log(minimumH2s / h2sGasPpm) / Math.log(concentrationAfterSpreading);
    double so2Range = Math.log(minimumSo2 / so2GasPpm) / Math.log(concentrationAfterSpreading);
   
    double range = Math.max(h2sRange, so2Range);
    if (range < 1) return;

    // System.out.println("calculated ppms / H2S: "+h2sGasPpm+", SO2: "+so2GasPpm+" / Range: "+range);
 */

    int range = 3;
    Collection<Entity> entities = location.getWorld().getNearbyEntities(location, range, range, range);
    
    for (Entity entity : entities) {
      double distance = entity.getLocation().distance(location);
      /*
      double intensity = Math.pow(0.11, distance);

      double localSo2GasPpm = so2GasPpm * intensity;
      double localH2sGasPpm = h2sGasPpm * intensity;

      // fumaroles can peak to 20 ppm
      // prolonged nausea, even coma if more than 1000 ppm
       */

      double intensity = distance / (double) range;
      if (entity instanceof LivingEntity) {
        LivingEntity livingEntity = (LivingEntity) entity;

        /*
        int poisonousLevel = 0;
        int nauseaLevel = 0;

        // ==== SO2 ====
        if (localSo2GasPpm > 30) nauseaLevel = 1;

        // 150ppm = die in few minutes
        if (localSo2GasPpm > 100) poisonousLevel = Math.max(poisonousLevel, (int) Math.min((localSo2GasPpm - 50) / 50, 5));
        if (localSo2GasPpm > 250 && !livingEntity.isInvulnerable()) livingEntity.setHealth(0);

        // ==== H2S ====
        if (localH2sGasPpm > 150) nauseaLevel = 1;

        // 150: respiratory tract
        if (localH2sGasPpm > 150) poisonousLevel = Math.max(poisonousLevel, 1);

        // 250ppm = risk of death
        if (localH2sGasPpm > 250) poisonousLevel = Math.max(poisonousLevel, (int) Math.min((localH2sGasPpm + 250) / 250, 5));
        
        // acute intoxication. just die.
        if (localH2sGasPpm > 1000 && !livingEntity.isInvulnerable()) livingEntity.setHealth(0);


        // ======== timespan ========
        double dilutionPerSecond = 0.95;
        double h2sTimespan = Math.log(minimumH2s / localH2sGasPpm) / Math.log(dilutionPerSecond);
        double so2Timespan = Math.log(minimumSo2 / localSo2GasPpm) / Math.log(dilutionPerSecond);

        int timespan = (int) (20 * Math.max(h2sTimespan, so2Timespan));
        */

        int timespan = 20 * 3;
        int poisonousLevel = 0;

        if (vent.getStatus().getScaleFactor() >= 0.1) {
          double heatValue = volcano.manager.getHeatValue(location);
          double multiplier = Math.random() < vent.getStatus().getScaleFactor() ? 5 : 1 + (Math.random() * 2);
          double total = Math.min(Math.max(heatValue * multiplier, 1), 5);

          poisonousLevel = (int) total;
        } else if (vent.getStatus() != VolcanoVentStatus.DORMANT) {
          if (vent.isInVent(location)) {
            poisonousLevel = Math.random() > 0.3 ? 1 : 0;
          }
        }

        if (poisonousLevel > 0) {
          poisonousLevel = Math.max((int) (poisonousLevel * intensity), 1);
          livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.POISON, timespan, poisonousLevel));
        }        
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
              }, 0,
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
        && volcano.manager.getHeatValue(location) >= 1 - vent.getStatus().getScaleFactor()
        && random.nextDouble() < vent.getStatus().getScaleFactor();
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
