package me.alex4386.plugin.typhon.volcano.crater;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.Volcano;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class VolcanoTremor {
    public VolcanoCrater crater;

    public boolean enable = true;
    public int scheduleID = -1;
    public int tremorCycleRate = 600;

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
                0L, tremorCycleRate);
    }


    public void runTremorCycle() {
        Random random = new Random();
        if (shouldIDoIt()) {
            Block block;
            block = getRandomTremorBlock();

            showTremorActivity(block, ((Math.random() / 2 + 0.5) * getTremorPower()));
        }
    }

    public void eruptTremor() {
        Random random = new Random();

        eruptTremor((Math.random() / 2 + 0.5) * getTremorPower());
    }

    public void eruptTremor(double eruptionScale) {
        if (shouldIDoIt()) {
            Volcano volcano = crater.getVolcano();
            Random random = new Random();

            showTremorActivity(volcano.location.getBlock(), eruptionScale * getTremorPower());
        }
    }

    public void tremorOnPlayer(Player player, int tremorLength, double power) {
        Volcano volcano = crater.getVolcano();

        AtomicInteger loop = new AtomicInteger();
        AtomicInteger termorLength = new AtomicInteger(tremorLength * (int) volcano.updateRate);

        AtomicInteger scheduleID = new AtomicInteger();
        Runnable tremorRunnable = (Runnable) () -> {
            if (player.isOnGround()) {
                float xDelta = (float) ((Math.random() - 0.5) * 0.04 * power);
                float zDelta = (float) ((Math.random() - 0.5) * 0.04 * power);
                float yawDelta = (float) ((Math.random() - 0.5) * 0.4 * power);
                float pitchDelta = (float) ((Math.random() - 0.5) * 0.4 * power);

                Location location = new Location(player.getWorld(),
                        player.getLocation().getX() + xDelta,
                        player.getLocation().getY(),
                        player.getLocation().getZ() + zDelta,
                        player.getLocation().getYaw() + yawDelta,
                        player.getLocation().getPitch() + pitchDelta);

                if (location.getBlock().getType().isAir()) player.teleport(location);
            }

            loop.getAndIncrement();

            if (loop.get() > termorLength.get()) {
                TyphonPlugin.plugin.getLogger().log(Level.INFO, player.getDisplayName()+" termor sequence Pass.");
                Bukkit.getScheduler().cancelTask(scheduleID.get());
            }
        };

        scheduleID.set(Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, tremorRunnable, 0, Math.min(1, volcano.updateRate / 5)));
    }

    public void showTremorActivity(Block block, double power) {
        Volcano volcano = crater.getVolcano();

        TyphonPlugin.plugin.getLogger().log(Level.FINEST, "[Volcano "+volcano.name+" Tremor] Running tremor for volcano " + volcano.name + " with power " + power + ".");

        for (Player player : Bukkit.getOnlinePlayers()) {
            int radius = (int) crater.longestFlowLength;
            Location location = player.getLocation();

            double distance = Math.sqrt(Math.pow(location.getBlockX() - block.getX(), 2) +
                    Math.pow(location.getBlockZ() - block.getZ(), 2));

            if (player.getWorld().getUID() == block.getWorld().getUID() &&
                    distance < radius && player.isOnGround()) {

                double impactFactor = 1.0 - (distance / radius);
                impactFactor = impactFactor > 0.2 ? impactFactor : 0;

                if (impactFactor > 0) {
                    tremorOnPlayer(player, (int) ( 2 + (Math.random() * 3)), power * impactFactor);
                }
            }
        }
    }

    public boolean shouldIDoIt() {
        Volcano volcano = crater.getVolcano();

        return Math.random() < volcano.status.getScaleFactor();
    }

    public double getTremorPower() {
        Volcano volcano = crater.getVolcano();

        switch(volcano.status) {
            case DORMANT:
                return 0.001;
            case MINOR_ACTIVITY:
                return 0.1;
            case MAJOR_ACTIVITY:
                return 1;
            case ERUPTING:
                return 10;
            default:
                return 0;
        }
    }
}
