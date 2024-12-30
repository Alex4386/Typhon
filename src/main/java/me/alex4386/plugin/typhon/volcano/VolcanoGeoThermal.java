package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonScheduler;
import me.alex4386.plugin.typhon.TyphonSounds;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

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
      EntitySpawnEvent.getHandlerList().unregisterAll(this);
      registeredEvent = false;
    }
  }

  public void runCraterGeothermal(VolcanoVent vent) {
    this.runCraterGeothermal(vent, this.getBlockToRunCraterCycle(vent));
  }
  public void runOuterCraterGeothermal(VolcanoVent vent) {
    this.runCraterGeothermal(vent, this.getBlockToRunOuterCraterCycle(vent));
  }

  public void runCraterGeothermal(VolcanoVent vent, Block block) {
    this.runCraterGeothermal(vent, block, true);
  }

  public void runCraterGeothermal(VolcanoVent vent, Block block, boolean onTop) {
    double scaleFactor = vent.getStatus().getScaleFactor();

    final Location targetLoc = block
      .getLocation()
      .add(0, 1, 0);

    scaleFactor *= (0.8) + (0.8 * Math.random());
    scaleFactor = Math.min(1, scaleFactor);
      
    boolean letOffSteam = false;
    boolean runPoison = false;

    if (scaleFactor >= 0.1) {
      if (Math.random() < scaleFactor) {
        this.burnNearbyEntities(targetLoc, 3);
        if (scaleFactor > 0.12) {
          if (Math.random() < ((scaleFactor - 0.12) / (1 - 0.12))) {
            this.playLavaGasReleasing(targetLoc);
          }
        }

        letOffSteam = true;
        runPoison = Math.random() < Math.sqrt(scaleFactor);
      }
    } else if (scaleFactor >= 0.04) {
      this.burnNearbyEntities(targetLoc, 1);
    
      letOffSteam = (Math.random() < (scaleFactor * 10));
      if (letOffSteam) {
        runPoison = Math.random() < Math.pow(scaleFactor, 0.8);
      }
    } else if (vent.getStatus() == VolcanoVentStatus.EXTINCT) {
      return;
    }

    if (letOffSteam) {
      this.playLavaGasReleasing(targetLoc);

      if (runPoison) {
        this.runVolcanicGas(block.getLocation());
      }
    }

    if (Math.random() < scaleFactor) {
      Block upperBlock = block.getRelative(BlockFace.UP);

      if (letOffSteam) {
        if (onTop) this.triggerUndergroundsCraterGeothermal(vent, block);

        if (runPoison || Math.random() < scaleFactor) {
          vent.volcano.metamorphism.evaporateBlock(block);
          vent.volcano.metamorphism.evaporateBlock(upperBlock);
        }
      }

      // check if there are trees nearby
      Block treeBlock = TyphonUtils.getHighestLocation(block.getLocation()).getBlock();

      if (!VolcanoComposition.isVolcanicRock(block.getType())) {
        vent.volcano.metamorphism.metamorphoseBlock(block, false);
      }

      if (TyphonUtils.isMaterialTree(upperBlock.getType())) {
        if (vent.isInVent(upperBlock.getLocation())) {
          vent.volcano.metamorphism.removeTree(upperBlock);
        } else {
          vent.volcano.metamorphism.killTree(upperBlock);
        }
      } else if (TyphonUtils.isMaterialPlant(upperBlock.getType())) {
        vent.volcano.metamorphism.removePlant(upperBlock);
      }


      if (TyphonUtils.isMaterialTree(treeBlock.getType())) {
        if (vent.isInVent(treeBlock.getLocation())) {
          vent.volcano.metamorphism.removeTree(treeBlock);
        } else {
          vent.volcano.metamorphism.killTree(treeBlock);
        }
      }
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
    double maxCount = Math.min(Math.max(1, circumference / 5), 1000 / cyclesPerTick);
    return maxCount;
  }

  public void runCraterGeoThermalCycle(VolcanoVent vent) {
    double thermalScale = Math.pow(vent.getStatus().getScaleFactor(), 2);
    double maxCount = this.getCraterCycleCount(vent);

    double cycleCount = thermalScale * maxCount * Math.random();
    volcano.logger.debug(VolcanoLogClass.GEOTHERMAL, "Triggering "+cycleCount+" cycles on crater");

    for (int i = 0; i < cycleCount; i++) {
      this.runCraterGeothermal(vent);
    }

    int extraCount = (int) (cycleCount * Math.pow(vent.getStatus().getScaleFactor(), 1.5));
    for (int i = 0; i < extraCount; i++) {
      this.runOuterCraterGeothermal(vent);
    }
  }

  public void runVolcanoGeoThermalCycle(VolcanoVent vent) {
    double volcanoRange = (Math.PI * Math.pow(vent.getBasinLength(), 2));

    double radius = 10;
    if (vent.getType() == VolcanoVentType.CRATER) {
      radius = vent.craterRadius;
    }

    volcanoRange -= (Math.PI * Math.pow(vent.craterRadius, 2));
    double craterRange = (Math.PI * Math.pow(radius, 2));
    double volcanoMultiplier = Math.max(Math.min(1000, volcanoRange / craterRange), 1);

    double maxCount = this.getCraterCycleCount(vent) * volcanoMultiplier;
    double thermalScale = Math.pow(vent.getStatus().getScaleFactor(), 1.5);
    double cycleCount = thermalScale * maxCount * Math.random();

    cycleCount = (int) cycleCount;
    volcano.logger.debug(VolcanoLogClass.GEOTHERMAL, "Triggering "+cycleCount+" cycles on volcano");

    if (vent.lavaFlow.hasAnyLavaFlowing()) {
      int lavaFlowCount = (int) (cycleCount / 2);
      List<Block> targets = vent.lavaFlow.getRandomLavaBlocks(lavaFlowCount);
      for (Block target: targets) {
        Block actualTarget = TyphonUtils.getRandomBlockInRange(target, 1, Math.max(2, (int) (2 + (4 * vent.getHeatValue(target.getLocation())))));
        this.runVolcanoGeoThermal(vent, actualTarget, false);
      }

      for (int i = 0; i < lavaFlowCount; i++) {
        this.runVolcanoGeoThermal(vent, this.getVolcanoGeoThermalBlock(vent));
      }
    } else if (vent.isCaldera()) {
      for (int i = 0; i < cycleCount; i++) {
        this.runVolcanoGeoThermal(vent, this.getCalderaGeoThermalBlock(vent));
      }
    }

    int extraCount = (int) (cycleCount * thermalScale);
    for (int i = 0; i < extraCount; i++) {
      this.runVolcanoGeoThermal(vent, this.getVolcanoGeoThermalBlock(vent));
    }
  }

  public Block getBlockToRunOuterCraterCycle(VolcanoVent vent) {
    Block block;

    double range = 50 * Math.pow(vent.getStatus().getScaleFactor(), 1.5);
    int craterRadius = vent.getRadius();
    double offset = VolcanoMath.getZeroFocusedRandom() * range;

    block = TyphonUtils
            .getHighestRocklikes(
                    TyphonUtils
                            .getRandomBlockInRange(
                                    vent.getCoreBlock(),
                                    0,
                                    (int) (craterRadius + offset)
                            )
            );
    return block;
  }

  public Block getBlockToRunCraterCycle(VolcanoVent vent) {
    return getBlockToRunCraterCycle(vent, getCraterGeoThermalRadius(vent));
  }

  public Block getBlockToRunCraterCycle(VolcanoVent vent, double geoThermalRadius) {
    Block block;

    int craterRadius = vent.craterRadius;
    double range = geoThermalRadius - craterRadius;
    double offset = VolcanoMath.getZeroFocusedRandom() * range;

    block = TyphonUtils
      .getHighestRocklikes(
          TyphonUtils
              .getRandomBlockInRange(
                  vent.getCoreBlock(),
                  0,
                  (int) (craterRadius + offset)
              )
      );
    return block;
  }

  
  public int getCraterGeoThermalRadius(VolcanoVent vent) {
    int geothermalRange = Math.min(100, (int) (vent.craterRadius * 2.5));

    return geothermalRange;
  }

  public Block getCalderaGeoThermalBlock(VolcanoVent vent) {
    if (!vent.isCaldera()) return null;

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
                                            vent.calderaRadius));

    return block;
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
                        vent.getBasinLength()));

    return block;
  }

  public void runVolcanoGeoThermal(VolcanoVent vent, Block block) {
    this.runVolcanoGeoThermal(vent, block, true);
  }

  public void runVolcanoGeoThermal(VolcanoVent vent, Block block, boolean allowSteam) {
    Block targetBlock = TyphonUtils.getHighestRocklikes(block);

    this.runGeothermalActivity(vent, targetBlock, allowSteam);
  }

  public void runGeothermalActivity(VolcanoVent vent, Block block, boolean allowSteam) {
    this.runGeothermalActivity(vent, block, allowSteam, true);
  }

  public void runGeothermalActivity(VolcanoVent vent, Block block, boolean allowSteam, boolean isTop) {
    Chunk chunk = block.getChunk();
    if (chunk.getInhabitedTime() == 0) {
      // skip geothermal process since it is not loaded yet.
      volcano.logger.debug(VolcanoLogClass.GEOTHERMAL, "Skip geothermal due to chunk was not loaded. chunk @ ("+chunk.getX()+", "+chunk.getZ()+")");
    }

    double scaleFactor = vent.getStatus().getScaleFactor();
    double heatValue = volcano.manager.getHeatValue(block.getLocation());

    Location targetLoc = block.getLocation().add(0,1,0);

    boolean letOffSteam = false;
    boolean runPoison = false;

    boolean backToNormalBiome = false;

    if (scaleFactor >= 0.1) {
      letOffSteam = true;
      runPoison = Math.random() < Math.max(scaleFactor, Math.sqrt(scaleFactor) * Math.pow(Math.min(1, heatValue / 0.85), 2));
    } else if (scaleFactor >= 0.04) {
      letOffSteam = (Math.random() < 0.5);
      runPoison = (Math.random() < scaleFactor);
      backToNormalBiome = true;
    } else {
      backToNormalBiome = true;
    }

    if (Math.random() < heatValue) {
      if (letOffSteam && allowSteam) {
        double burnRange = (int) (Math.sqrt(vent.getStatus().getScaleFactor()) * 6) * heatValue;

        if (isTop) this.triggerUndergroundsVolcanoGeothermal(vent, block);
        if (isTop) {
          if (Math.random() < 0.0002 && vent.getStatus().getScaleFactor() > 0.3) {
            // generate tuff ring
            this.createTuffRing(block);
          }
        }

        this.playLavaGasReleasing(targetLoc);
        if (burnRange > 0) {
          this.burnNearbyEntities(targetLoc, 3);
        }

        if (runPoison) {
          this.runVolcanicGas(targetLoc);
        }

        if (burnRange > 1) {
          Block upperBlock = block.getRelative(BlockFace.UP);
          vent.volcano.metamorphism.evaporateBlock(block);

          if (vent.isStarted())
            vent.volcano.metamorphism.evaporateBlock(upperBlock);

          // check if there are trees nearby
          Block treeBlock = TyphonUtils.getHighestLocation(block.getLocation()).getBlock();

          if (!VolcanoComposition.isVolcanicRock(block.getType())) {
            vent.volcano.metamorphism.metamorphoseBlock(block, false);

            if (TyphonUtils.isMaterialTree(upperBlock.getType())) {
              vent.volcano.metamorphism.killTree(upperBlock);
            } else if (TyphonUtils.isMaterialPlant(upperBlock.getType())) {
              vent.volcano.metamorphism.removePlant(upperBlock);
            }
          }

          double killTree = Math.pow(vent.getStatus().getScaleFactor(), 2);
          if (Math.random() < killTree) {
            if (TyphonUtils.isMaterialTree(treeBlock.getType())) {
              vent.volcano.metamorphism.killTree(treeBlock);
            }
          }
        }
      }
    }

    if (backToNormalBiome) {
      if (isTop) {
        this.volcano.succession.returnToNormalBiome(block);
      }
    }
  }

  public void createTuffRing(Block block) {
    int radius = 2 + (int) (Math.random() * 3);

    int deep = Math.random() < 2 ? 1 : 2;
    double actualRadius = (Math.pow(radius, 2)+Math.pow(deep, 2))/(2*deep);
    Block center = block.getRelative(0, radius - deep, 0);

    List<Block> tuffRingBlocks = VolcanoMath.getCylinder(block, radius, -deep);
    tuffRingBlocks.removeIf(block1 -> block.getLocation().distance(center.getLocation()) > actualRadius);

    block.getWorld().createExplosion(block.getLocation(), (float) radius, false, false);

    for (Block tuffRingBlock : tuffRingBlocks) {
      this.volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(tuffRingBlock, Material.AIR);

      Block bottomBlock = tuffRingBlock.getRelative(BlockFace.DOWN);
      if (!bottomBlock.getType().isAir())
        this.volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(bottomBlock, Material.TUFF);
    }
  }

  public void triggerUndergroundsVolcanoGeothermal(VolcanoVent vent, Block block) {
    this.triggerUndergrounds(block, (underBlock) -> {
      this.runUndergroundVolcanoGeothermalActivity(vent, underBlock);
    });
  }

  public void triggerUndergroundsCraterGeothermal(VolcanoVent vent, Block block) {
    this.triggerUndergrounds(block, (underBlock) -> {
      this.runUndergroundCraterGeothermalActivity(vent, underBlock);
    });
  }

  public void triggerUndergrounds(Block block, Consumer<Block> blockConsumer) {
    int radius = 16;
    int scanRadius = (block.getY() - block.getWorld().getMinHeight()) / 2;
    Collection<Entity> entities = block.getWorld().getNearbyEntities(block.getLocation().subtract(0, scanRadius, 0), radius, scanRadius, radius);
    entities.removeIf(e -> e.getLocation().getBlockY() >= TyphonUtils.getHighestRocklikes(e.getLocation()).getY() - 3);

    if (entities.size() > 0) {
      Block scanBaseBlock = block.getRelative(0, 3, 0);
      List<Block> underGroundActivityBlocks = new ArrayList<>();

      for (Block scanBlock = scanBaseBlock; scanBlock.getY() >= scanBlock.getWorld().getMinHeight(); scanBlock = scanBlock.getRelative(BlockFace.DOWN)) {
        if (scanBlock.getType().isAir()) continue;

        if (TyphonUtils.isMaterialRocklikes(scanBlock.getType())) {
          if (scanBlock.getRelative(BlockFace.UP).getType().isAir()) {
            underGroundActivityBlocks.add(scanBlock);
          }
        }
      }

      for (Block activityBlock : underGroundActivityBlocks) {
        blockConsumer.accept(activityBlock);
      }
    }
  }

  public void runUndergroundVolcanoGeothermalActivity(VolcanoVent vent, Block block) {
    this.runCraterGeothermal(vent, block, false);

    int diggingInY = TyphonUtils.getHighestRocklikes(block).getY() - block.getY();
    if (block.getRelative(BlockFace.UP).getType().isAir()) {
      if (vent.isFlowingLava() && Math.random() < 0.25) {
        if (diggingInY > 15) {
          double distance = TyphonUtils.getTwoDimensionalDistance(block.getLocation(), vent.getNearestCoreBlock(block.getLocation()).getLocation());
          int summitY = vent.getSummitBlock().getY();
          double magmaConduitBase = vent.getRadius() + (summitY / Math.sqrt(3));
          if (distance < magmaConduitBase + (10 * Math.random())) {
            Block lavaTarget = block.getRelative(BlockFace.UP);
            this.playLavaGasReleasing(lavaTarget.getLocation());

            vent.lavaFlow.flowLava(lavaTarget);
            return;
          }
        }
      }

      if (diggingInY > 32) {
        double probability = (diggingInY - 32) / 32;
        if (Math.random() < probability) {
          this.playLavaGasReleasing(block.getLocation());
        }
      }
    }
  }

  public void runUndergroundCraterGeothermalActivity(VolcanoVent vent, Block block) {
    this.runGeothermalActivity(vent, block, true, false);

    int diggingInY = TyphonUtils.getHighestRocklikes(block).getY() - block.getY();
    if (block.getRelative(BlockFace.UP).getType().isAir()) {
      if (vent.isFlowingLava() && Math.random() < 0.5) {
        if (diggingInY > 15) {
          Block lavaTarget = block.getRelative(BlockFace.UP);

          vent.lavaFlow.flowLava(lavaTarget);
          return;
        }
      }
    }
  }

  public void playLavaGasReleasing(Location location) {
    if (Math.random() < 0.2) {
      TyphonSounds.EARTH_CRACKING.play(
              location,
                SoundCategory.BLOCKS,
              0.5f,
              1f
      );
    }

    location
    .getWorld()
    .playSound(
      location,
      Sound.ENTITY_BREEZE_WIND_BURST,
      SoundCategory.BLOCKS,
            (float) (0.5f + (Math.random() * 0.5f)),
      0f);


    // gas releasing up
    TyphonUtils.createRisingSteam(
      location,
      1,
      2
    );
  }

  public void burnNearbyEntities(Location location, double range) {
    Collection<Entity> entities = location.getWorld().getNearbyEntities(location, range, range, range);
    
    for (Entity entity : entities) {
      double distance = entity.getLocation().distance(location);
      double fireTicks = Math.max(60, (600 * volcano.manager.getHeatValue(location)) * (distance / range));
      
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

    double scaleValue = Math.sqrt(vent.getStatus().getScaleFactor()) * 6;
    int range = (int) (scaleValue * Math.pow(vent.getHeatValue(location), 1.1));
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
      double heatValue = volcano.manager.getHeatValue(location);
      double multiplier = Math.random() < vent.getStatus().getScaleFactor() ? 5 : 1 + (Math.random() * 4);

      if (entity instanceof LivingEntity && distance <= range) {
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

        int timespan = (int) (25 * scaleValue * Math.sqrt(1 - intensity));
        int poisonousLevel = 0;

        if (vent.getStatus().getScaleFactor() >= 0.1) {
          double total = Math.min(Math.max(heatValue * multiplier, 1), 5);

          poisonousLevel = (int) total;
        } else if (vent.getStatus() != VolcanoVentStatus.DORMANT) {
          if (vent.isInVent(location)) {
            poisonousLevel = Math.random() > 0.15 ? 1 : 0;
          }
        }

        if (poisonousLevel > 0) {
          poisonousLevel = Math.max((int) (poisonousLevel * intensity), 1);

          // check if there is existing poison effect
          if (livingEntity.hasPotionEffect(PotionEffectType.POISON)) {
              PotionEffect poisonEffect = livingEntity.getPotionEffect(PotionEffectType.POISON);
              if (poisonEffect != null) {
                int newPoisonLevel = Math.max(poisonEffect.getAmplifier(), poisonousLevel);
                int newTimespan = poisonEffect.getDuration() + timespan;
                livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.POISON, newTimespan, newPoisonLevel));
              }
          } else {
              livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.POISON, timespan, poisonousLevel));
          }

          // the entity is not affected by poison, then we should damage the entity.
          if (TyphonUtils.isNotAffectedByPoisonEffect(entity.getType())) {
            int calculateDamageWithTimespan = (int) (poisonousLevel * (timespan / 20.0));
            livingEntity.damage(calculateDamageWithTimespan);
          }

          EntityEquipment equipment = livingEntity.getEquipment();
          if (equipment != null) {
            this.doSOxDamage(equipment.getHelmet(), poisonousLevel);
            this.doSOxDamage(equipment.getChestplate(), poisonousLevel);
            this.doSOxDamage(equipment.getLeggings(), poisonousLevel);
            this.doSOxDamage(equipment.getBoots(), poisonousLevel);

            this.doSOxDamage(equipment.getItemInMainHand(), poisonousLevel);
            this.doSOxDamage(equipment.getItemInOffHand(), poisonousLevel);
          }

          if (poisonousLevel > 3) {
            if (Math.random() < ((double) (poisonousLevel - 3) / 2)) {
              livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, timespan, 1));
            }
          }
        }
      } else if (entity instanceof Item) {
        double total = Math.min(Math.max(heatValue * multiplier, 1), 5);

        int acidLevel = (int) (total * intensity);
        if (acidLevel > 0) {
          Item item = (Item) entity;
          ItemStack stack = item.getItemStack();
          this.doSOxDamage(stack, acidLevel);
        }
      }
    }
  }

  public void doSOxDamage(ItemStack stack, int level) {
    if (stack == null) {
      return;
    }

    ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      if (meta instanceof Damageable) {
        ((Damageable) meta).damage(getSOxDamageMultiplier(stack.getType()) * level);
      }
    }
  }

  public double getSOxDamageMultiplier(Material material) {
    String name = TyphonUtils.toLowerCaseDumbEdition(material.name());

    if (name.startsWith("wood")) { return 1; }
    if (name.startsWith("iron")) { return 1.5; }

    return 0;
  }

  public void registerTask() {
    if (scheduleID == -1) {
      scheduleID = TyphonScheduler.registerGlobalTask(
              (Runnable) () -> {
                if (enable) {
                  for (VolcanoVent vent : volcano.manager.getVents()) {
                    this.runGeoThermalCycle(vent);
                  }
                }
              },
              Math.min(
                  (long) ((geoThermalUpdateRate / 20.0)
                      * volcano.updateRate),
                  1));
    }
  }

  public void unregisterTask() {
    if (scheduleID != -1) {
      TyphonScheduler.unregisterTask(scheduleID);
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
        String name = TyphonUtils.toLowerCaseDumbEdition(e.getBlock().getType().name());
        if (name.contains("snow")) {
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
        // THIS IS WATER vaporizing, not volcanic gas escaping from ground.
        // therefore use only createRisingSteam
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
  public void onEntitySpawn(EntitySpawnEvent e) {
    List<VolcanoVent> vents = volcano.manager.getVents();

    // if the entity is mob or other friendly creatures, then we should check if it should be spawned.
    if (e.getEntity() instanceof Monster || e.getEntity() instanceof Animals) {
      for (VolcanoVent vent : vents) {
        if (shouldDoIt(vent, e.getLocation())) {
          e.setCancelled(true);
        }
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