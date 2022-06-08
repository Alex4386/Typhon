package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoCircleOffsetXZ;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.Map;

public class VolcanoAsh {
    VolcanoVent vent;

    public static int ashFallScheduleId = -1;
    public static int updatesPerSeconds = 4;

    public void registerTask() {
        if (ashFallScheduleId < 0) {
            ashFallScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) () -> {
                                for (Map.Entry<String, Volcano> entry : TyphonPlugin.listVolcanoes.entrySet()) {
                                    Volcano volcano = entry.getValue();

                                    List<VolcanoVent> vents = volcano.manager.getVents();
                                    for (VolcanoVent vent : vents) {
                                        if (vent.erupt.isErupting()) {
                                            vent.ash.createAshPlume();
                                            vent.ash.triggerAshFall();
                                        }
                                    }
                                }
                            },
                            0L,
                            (long) TyphonPlugin.minecraftTicksPerSeconds
                                    / updatesPerSeconds);
        }
    }

    public void unregisterTask() {
        if (ashFallScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(ashFallScheduleId);
            ashFallScheduleId = -1;
        }
    }

    public VolcanoAsh(VolcanoVent vent) {
        this.vent = vent;
    }

    public void initialize() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.ASH, "Intializing VolcanoAsh for vent " + vent.getName());
        this.registerTask();
    }

    public void shutdown() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.ASH, "Shutting down VolcanoAsh for vent " + vent.getName());
        this.unregisterTask();
    }

    public void createAshPlume() {
        createAshPlume(
                TyphonUtils.getHighestLocation(this.vent.selectCoreBlock().getLocation())
                        .add(0, 1, 0));
    }

    public void createAshPlume(Location loc) {
        VolcanoEruptStyle style = vent.erupt.getStyle();
        if (style == VolcanoEruptStyle.HAWAIIAN) {
            TyphonUtils.spawnParticleWithVelocity(Particle.ASH, loc, 0, 1, 0, 0.25, 0);
        } else if (style == VolcanoEruptStyle.STROMBOLIAN
                || style == VolcanoEruptStyle.VULCANIAN
                || style == VolcanoEruptStyle.PELEAN) {
            int count = this.vent.explosion.settings.minBombCount
                    + ((int) Math.random() * this.vent.explosion.settings.maxBombCount);
            double multiplier = style.ashMultiplier;

            for (int i = 0; i < count * multiplier; i++) {
                VolcanoCircleOffsetXZ xz = VolcanoMath.getCenterFocusedCircleOffset(
                        loc.getBlock(), this.vent.craterRadius);
                Location finalLoc = TyphonUtils.getHighestLocation(loc.add(xz.x, 0, xz.z)).add(0, 1, 0);

                TyphonUtils.spawnParticleWithVelocity(
                        Particle.CAMPFIRE_SIGNAL_SMOKE,
                        finalLoc,
                        0,
                        (int) (3 * multiplier),
                        (Math.random() - 0.5) / 2,
                        0.4,
                        (Math.random() - 0.5) / 2);
            }
        }
    }

    public void triggerAshFall() {
        triggerAshFall(
                TyphonUtils.getHighestLocation(this.vent.selectCoreBlock().getLocation())
                        .add(0, 1, 0));
    }

    public void triggerAshFall(Location loc) {
        VolcanoEruptStyle style = vent.erupt.getStyle();

        if (style == VolcanoEruptStyle.STROMBOLIAN
                || style == VolcanoEruptStyle.VULCANIAN
                || style == VolcanoEruptStyle.PELEAN) {
            int count = this.vent.explosion.settings.minBombCount
                    + ((int) Math.random() * this.vent.explosion.settings.maxBombCount);
            double multiplier = style.ashMultiplier;

            for (int i = 0; i < count * multiplier; i++) {
                VolcanoCircleOffsetXZ xz = VolcanoMath.getCenterFocusedCircleOffset(
                        loc.getBlock(),
                        0,
                        (int) Math.round(this.vent.longestFlowLength * 0.5 * multiplier));
                Location finalLoc = TyphonUtils.getHighestRocklikes(loc.add(xz.x, 0, xz.z))
                        .getRelative(BlockFace.UP)
                        .getLocation();

                TyphonUtils.spawnParticleWithVelocity(
                        Particle.CAMPFIRE_SIGNAL_SMOKE,
                        finalLoc,
                        0,
                        (int) (3 * multiplier),
                        0,
                        -0.4,
                        0);

                finalLoc.getBlock().setType(Material.TUFF);
            }
        }
    }
}
