package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class VolcanoTremor {
    public VolcanoVent vent;

    public boolean enable = true;
    public int scheduleID = -1;
    public int tremorCycleRate = 200;

    public VolcanoTremor(VolcanoVent vent) {
        this.vent = vent;
    }

    public Block getRandomTremorBlock() {
        Volcano volcano = vent.getVolcano();

        Location loc;
        Random random = new Random();
        int x = vent.location.getBlockX() + (int) (random.nextGaussian() * (vent.craterRadius * 2));
        int z = vent.location.getBlockZ() + (int) (random.nextGaussian() * (vent.craterRadius * 2));
        loc = new Location(volcano.location.getWorld(),
                x,
                volcano.location.getWorld().getHighestBlockYAt(x,z),
                z);


        return loc.getBlock();
    }

    public void registerTask() {
        if (scheduleID >= 0) { return; }
        Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin,
                () -> {
                    if (!vent.isExploding()) {
                        // runTremorCycle();
                    }
                },
                0L, Math.max(1, tremorCycleRate / 20 * vent.volcano.updateRate));
    }

    public void unregisterTask() {
        if (scheduleID < 0) return;
        Bukkit.getScheduler().cancelTask(scheduleID);
        scheduleID = -1;
    }

    public void initialize() {
        this.vent.volcano.logger.log(VolcanoLogClass.TREMOR, "Intializing Volcano Tremor...");
        this.registerTask();
    }

    public void shutdown() {
        this.vent.volcano.logger.log(VolcanoLogClass.TREMOR, "Shutting down Volcano Tremor...");
        this.unregisterTask();
    }

    public void runTremorCycle() {
        Volcano volcano = vent.getVolcano();
        if (shouldIDoIt()) {
            volcano.logger.debug(VolcanoLogClass.TREMOR, "Volcano Tremor Cycle Started.");

            Block block;
            block = getRandomTremorBlock();

            showTremorActivity(block, ((Math.random() / 2 + 0.5) * getTremorPower()));
        }
    }

    public void explosionTremor() {
        explosionTremor((Math.random() / 2 + 0.5) * getTremorPower(VolcanoVentStatus.ERUPTING));
    }

    public void explosionTremor(double power) {
        if (shouldIDoIt()) {
            showTremorActivity(getRandomTremorBlock(), power);
        }
    }

    public void tremorOnEntity(Entity entity, int tremorLength, double power) {
        Volcano volcano = vent.getVolcano();

        AtomicInteger loop = new AtomicInteger();
        AtomicInteger termorLength = new AtomicInteger(tremorLength * (int) volcano.updateRate);

        AtomicReference<Location> prevLocation = new AtomicReference<>(entity.getLocation());

        AtomicInteger scheduleID = new AtomicInteger();
        Runnable tremorRunnable = (Runnable) () -> {
            String name;
            if (entity instanceof Player) {
                Player player = (Player) entity;
                name = player.getName();
            } else {
                name = entity.getType().name();
            }

            if (entity.isOnGround()) {
                float xDelta = (float) ((Math.random() - 0.5) * 0.04 * power);
                float zDelta = (float) ((Math.random() - 0.5) * 0.04 * power);
                float yawDelta = (float) ((Math.random() - 0.5) * 0.4 * power);
                float pitchDelta = (float) ((Math.random() - 0.5) * 0.4 * power);

                Location newLocation = entity.getLocation();

                if (newLocation.distance(prevLocation.get()) != 0 && prevLocation.get().getYaw() != newLocation.getYaw() && prevLocation.get().getPitch() != newLocation.getPitch()) {
                    loop.getAndIncrement();
                } else {
                    Location location = new Location(entity.getWorld(),
                            entity.getLocation().getX() + xDelta,
                            entity.getLocation().getY(),
                            entity.getLocation().getZ() + zDelta,
                            entity.getLocation().getYaw() + yawDelta,
                            entity.getLocation().getPitch() + pitchDelta);

                    if (location.getBlock().getType().isAir()) entity.teleport(location);
                }
            }

            loop.getAndIncrement();

            if (loop.get() > termorLength.get()) {
                volcano.logger.debug(VolcanoLogClass.TREMOR, name+" termor sequence Pass.");
                Bukkit.getScheduler().cancelTask(scheduleID.get());
            }

            prevLocation.set(entity.getLocation());
        };

        scheduleID.set(Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, tremorRunnable, 0, 1));
    }

    public void showTremorActivity(Block block, double power) {
        Volcano volcano = vent.getVolcano();
        /*
        block.getWorld().playSound(
                block.getLocation(),

                power * 10f
        );
        */

        TyphonPlugin.plugin.getLogger().log(Level.FINEST, "[Volcano "+volcano.name+" Tremor] Running tremor for volcano " + volcano.name + " with power " + power + ".");

        for (Player player : Bukkit.getOnlinePlayers()) {
            int radius = (int) vent.longestFlowLength * 2;
            Location location = player.getLocation();

            double distance = Math.sqrt(Math.pow(location.getBlockX() - block.getX(), 2) +
                    Math.pow(location.getBlockZ() - block.getZ(), 2));

            if (player.getWorld().getUID() == block.getWorld().getUID() && distance < radius) {

                double impactFactor = vent.getHeatValue(player.getLocation());

                Entity[] entities = player.getLocation().getChunk().getEntities();
                Map<Entity, Location> entityLocation = new HashMap<>();
                for (Entity entity : entities) {
                    if (entity.isOnGround()) {
                        if (entity.getType().isAlive()) {
                            entityLocation.put(entity, entity.getLocation());
                        }
                    }
                }

                Bukkit.getScheduler().runTaskLater(
                        TyphonPlugin.plugin,
                        (Runnable) () -> {
                            for (Entity entity : entities) {
                                Location prevLocation = entityLocation.get(entity);
                                if (entity != null) {
                                    Location nowLocation = entity.getLocation();

                                    if (prevLocation != null) {
                                        if (impactFactor > 0 && prevLocation.distance(nowLocation) == 0 && prevLocation.getYaw() == nowLocation.getYaw() && prevLocation.getPitch() == nowLocation.getPitch()) {
                                            tremorOnEntity(entity, (int) ( 2 + Math.max(Math.random() * impactFactor, 2)), power * impactFactor);
                                        }
                                    }

                                    entityLocation.remove(entity);
                                    entityLocation.put(entity, nowLocation);
                                }
                            }
                        },
                        2L
                );

            }
        }
    }

    public boolean shouldIDoIt() {
        Volcano volcano = vent.getVolcano();

        return Math.random() < vent.status.getScaleFactor();
    }

    public double getTremorPower() {
        return getTremorPower(this.vent.status);
    }

    public double getTremorPower(VolcanoVentStatus status) {
        switch(status) {
            case DORMANT:
                return 0.001;
            case MINOR_ACTIVITY:
                return 0.1;
            case MAJOR_ACTIVITY:
                return 0.9;
            case ERUPTING:
                return 3;
            default:
                return 0;
        }
    }
}
