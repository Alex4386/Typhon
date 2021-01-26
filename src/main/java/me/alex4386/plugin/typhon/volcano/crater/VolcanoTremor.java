package me.alex4386.plugin.typhon.volcano.crater;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.Volcano;
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
    public VolcanoCrater crater;

    public boolean enable = true;
    public int scheduleID = -1;
    public int tremorCycleRate = 200;

    public VolcanoTremor(VolcanoCrater crater) {
        this.crater = crater;
    }

    public Block getRandomTremorBlock() {
        Volcano volcano = crater.getVolcano();

        Location loc;
        Random random = new Random();
        int x = crater.location.getBlockX() + (int) (random.nextGaussian() * (crater.craterRadius * 2));
        int z = crater.location.getBlockZ() + (int) (random.nextGaussian() * (crater.craterRadius * 2));
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
                    runTremorCycle();
                },
                0L, Math.max(1, tremorCycleRate / 20 * crater.volcano.updateRate));
    }

    public void unregisterTask() {
        if (scheduleID < 0) return;
        Bukkit.getScheduler().cancelTask(scheduleID);
        scheduleID = -1;
    }

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    public void runTremorCycle() {
        if (shouldIDoIt()) {
            Block block;
            block = getRandomTremorBlock();

            showTremorActivity(block, ((Math.random() / 2 + 0.5) * getTremorPower()));
        }
    }

    public void eruptTremor() {
        Random random = new Random();

        eruptTremor((Math.random() / 2 + 0.5) * getTremorPower(VolcanoCraterStatus.ERUPTING));
    }

    public void eruptTremor(double power) {
        if (shouldIDoIt()) {
            Volcano volcano = crater.getVolcano();
            Random random = new Random();

            showTremorActivity(getRandomTremorBlock(), power);
        }
    }

    public void tremorOnEntity(Entity entity, int tremorLength, double power) {
        Volcano volcano = crater.getVolcano();

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
                TyphonPlugin.plugin.getLogger().log(Level.INFO, name+" termor sequence Pass.");
                Bukkit.getScheduler().cancelTask(scheduleID.get());
            }

            prevLocation.set(entity.getLocation());
        };

        scheduleID.set(Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, tremorRunnable, 0, 1));
    }

    public void showTremorActivity(Block block, double power) {
        Volcano volcano = crater.getVolcano();

        TyphonPlugin.plugin.getLogger().log(Level.FINEST, "[Volcano "+volcano.name+" Tremor] Running tremor for volcano " + volcano.name + " with power " + power + ".");

        for (Player player : Bukkit.getOnlinePlayers()) {
            int radius = (int) crater.longestFlowLength * 2;
            Location location = player.getLocation();

            double distance = Math.sqrt(Math.pow(location.getBlockX() - block.getX(), 2) +
                    Math.pow(location.getBlockZ() - block.getZ(), 2));

            if (player.getWorld().getUID() == block.getWorld().getUID() && distance < radius) {

                double impactFactor = crater.getHeatValue(player.getLocation());

                Entity[] entities = player.getLocation().getChunk().getEntities();
                Map<Entity, Location> entityLocation = new HashMap<>();
                for (Entity entity : entities) {
                    entityLocation.put(entity, entity.getLocation());
                }

                Bukkit.getScheduler().runTaskLater(
                        TyphonPlugin.plugin,
                        (Runnable) () -> {
                            for (Entity entity : entities) {
                                Location prevLocation = entityLocation.get(entity);
                                Location nowLocation = entity.getLocation();

                                if (impactFactor > 0 && prevLocation.distance(nowLocation) == 0 && prevLocation.getYaw() == nowLocation.getYaw() && prevLocation.getPitch() == nowLocation.getPitch()) {
                                    tremorOnEntity(entity, (int) ( 2 + Math.max(Math.random() * impactFactor, 2)), power * impactFactor);
                                }
                            }
                        },
                        2L
                );

            }
        }
    }

    public boolean shouldIDoIt() {
        Volcano volcano = crater.getVolcano();

        return Math.random() < crater.status.getScaleFactor();
    }

    public double getTremorPower() {
        return getTremorPower(this.crater.status);
    }

    public double getTremorPower(VolcanoCraterStatus status) {
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
