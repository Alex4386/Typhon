package me.alex4386.plugin.typhon.volcano.erupt;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoNamer;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentGenesis;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Location;
import org.json.simple.JSONObject;

import java.util.List;

public class VolcanoErupt {
    VolcanoVent vent;
    VolcanoEruptStyle style = VolcanoEruptStyle.STROMBOLIAN;

    private boolean erupting = false;

    public VolcanoErupt(VolcanoVent vent) {
        this.vent = vent;
    }

    public void start() {
        if (this.vent != null && this.vent.isKillSwitchActive())
            return;

        if (this.erupting)
            return;

        if (this.style.flowsLava()) {
            this.startFlowingLava();
        }

        if (this.style.isExplosive()) {
            this.startExploding();
        }

        if (this.style == VolcanoEruptStyle.HAWAIIAN) {
            // this kind of eruption generates bigger base, therefore we should reset baseY.
            this.vent.bombs.resetBaseY();
        } else if (this.style == VolcanoEruptStyle.LAVA_DOME) {
            this.vent.lavadome.configure();
        }

        this.vent.resetCurrentMetrics();
        this.erupting = true;
    }

    public void stop() {
        this.stopExploding();
        this.stopFlowingLava();

        this.erupting = false;
    }

    public void autoConfig() {
        this.autoConfig(true);
    }

    public void autoConfig(boolean updateSilicate) {
        if (this.style == VolcanoEruptStyle.HAWAIIAN) {
            this.vent.lavaFlow.settings.silicateLevel = 0.45 + (Math.random() * (0.49 - 0.45));
            this.vent.lavaFlow.settings.flowed = 10;
            this.vent.lavaFlow.settings.delayFlowed = 14;

            this.vent.explosion.settings.minBombCount = 3;
            this.vent.explosion.settings.maxBombCount = 6;
            this.vent.explosion.settings.queueSize = 2;

        } else {
            this.vent.explosion.enabled = true;
            if (this.style == VolcanoEruptStyle.STROMBOLIAN
                    || this.style == VolcanoEruptStyle.VULCANIAN) {
                if (this.vent.lavaFlow.settings.silicateLevel > 0.57 && updateSilicate) {
                    this.vent.lavaFlow.settings.silicateLevel = 0.45 + (Math.random() * (0.57 - 0.45));
                }

                this.vent.lavaFlow.settings.delayFlowed = 10;
                this.vent.lavaFlow.settings.flowed = 7;

                this.vent.explosion.settings.minBombCount = 10;
                this.vent.explosion.settings.maxBombCount = 20;
                this.vent.explosion.settings.queueSize = 15;

                 if (this.style == VolcanoEruptStyle.VULCANIAN) {
                    if (this.vent.lavaFlow.settings.silicateLevel < 0.57 && updateSilicate) {
                        this.vent.lavaFlow.settings.silicateLevel = 0.57 + (Math.random() * (0.62 - 0.57));
                    }

                    this.vent.explosion.settings.minBombCount = 50;
                    this.vent.explosion.settings.maxBombCount = 75;
                    this.vent.explosion.settings.queueSize = 25;
                } else if (this.style == VolcanoEruptStyle.PELEAN) {
                    if (this.vent.lavaFlow.settings.silicateLevel < 0.72 && updateSilicate) {
                        this.vent.lavaFlow.settings.silicateLevel = 0.61 + (Math.random() * (0.72 - 0.61));
                    }
                }
                // TODO: requires build up of lava dome before hand
            } else {
            }
        }
    }

    public Location getVentLocation() {
        Volcano volcano = this.vent.getVolcano();
        boolean isCalderaAvailable = this.vent.craterRadius + 10 < this.vent.calderaRadius * 0.7;

        if (this.vent.isCaldera() && isCalderaAvailable) {
            Location location = TyphonUtils.getRandomBlockInRange(this.vent.getCoreBlock(), this.vent.craterRadius + 10, (int) (this.vent.calderaRadius * 0.7)).getLocation();

            return location;
        } else if (volcano.isVolcanicField()) {
            Location location = TyphonUtils.getRandomBlockInRange(this.vent.getCoreBlock(), 40, (int) Math.max(80, this.vent.getVolcanicRadius())).getLocation();

            if (TyphonUtils.getTwoDimensionalDistance(location, this.vent.location) > volcano.fieldRange) {
                return null;
            }

            return location;
        } else {
            double coneSize = this.vent.getVolcanicRadius();
            int minDistance = (int) Math.max(this.vent.craterRadius + 50, coneSize / 2);
            int maxDistance = (int) Math.max(this.vent.longestFlowLength, Math.max(minDistance, coneSize + 60));
            Location location = TyphonUtils.getRandomBlockInRange(this.vent.getCoreBlock(), minDistance, maxDistance).getLocation();

            if (volcano.manager.getNearestVent(location).isInVent(location)) {
                return null;
            }

            return location;
        }
    }


    public VolcanoVentType getNewVentType() {
        double fissureProbability = 0;
        if (this.vent.lavaFlow.settings.silicateLevel < 0.53) {
            fissureProbability = Math.max(1 - Math.min(0.8, Math.max((this.vent.lavaFlow.settings.silicateLevel - 0.47 / (0.53 - 0.47)), 0)), 0) * 0.75;
        }

        if (fissureProbability > Math.random()) {
            return VolcanoVentType.FISSURE;
        }

        return VolcanoVentType.CRATER;
    }

    public VolcanoVent openFissure() {
        String name = "";
        boolean generated = false;

        Volcano volcano = this.vent.getVolcano();
        for (int key = 1; key < 999; key++) {
            name = "fiss_"+key;
            if (volcano.subVents.get(name) == null) {
                generated = true;
                break;
            }
        }

        Location location = this.getVentLocation();
        if (location == null) generated = false;

        if (generated) {
            VolcanoVent newVent = new VolcanoVent(volcano, location, name);

            VolcanoVentType type = this.getNewVentType();

            newVent.erupt.setStyle(this.vent.erupt.getStyle());

            // propagate eruption setup
            newVent.explosion.settings.explosionSize = this.vent.explosion.settings.explosionSize;
            newVent.explosion.settings.maxBombCount = this.vent.explosion.settings.maxBombCount;
            newVent.explosion.settings.minBombCount = this.vent.explosion.settings.minBombCount;
            newVent.explosion.settings.damagingExplosionSize = this.vent.explosion.settings.damagingExplosionSize;

            newVent.lavaFlow.settings.flowed = this.vent.lavaFlow.settings.flowed;
            newVent.lavaFlow.settings.flowing = this.vent.lavaFlow.settings.flowing;

            newVent.lavaFlow.settings.silicateLevel = this.vent.lavaFlow.settings.silicateLevel;

            // if eruption style changes, apply it.
            if (newVent.erupt.getStyle() == VolcanoEruptStyle.HAWAIIAN && type == VolcanoVentType.CRATER) {
                newVent.erupt.setStyle(VolcanoEruptStyle.STROMBOLIAN);

                newVent.name = VolcanoNamer.generate();
                name = newVent.name;

                newVent.explosion.settings.minBombCount = 3;
                newVent.explosion.settings.maxBombCount = 5;
            } else if (newVent.erupt.getStyle() == VolcanoEruptStyle.STROMBOLIAN && type == VolcanoVentType.FISSURE) {
                newVent.erupt.setStyle(VolcanoEruptStyle.HAWAIIAN);

                newVent.fissureAngle = this.vent.fissureAngle;

                newVent.explosion.settings.minBombCount = 0;
                newVent.explosion.settings.maxBombCount = 2;
            }

            // volcanic vent genesis type
            newVent.genesis = VolcanoVentGenesis.MONOGENETIC;
            if (!volcano.isVolcanicField() && Math.random() < 0.25) {
                newVent.genesis = VolcanoVentGenesis.POLYGENETIC;
            }

            newVent.setType(type);
            newVent.name = name;

            volcano.addVolcanoVent(newVent);

            return newVent;
        } else {
            return null;
        }
    }

    public void updateVentConfig() {
        if (this.vent == null)
            return;

        if (this.style == VolcanoEruptStyle.HAWAIIAN
                || this.style == VolcanoEruptStyle.STROMBOLIAN) {
            if (this.vent.getType() == VolcanoVentType.FISSURE) {
                int prevFissureLength = this.vent.fissureLength;
                int targetLength = (int) Math.max(
                    this.vent.getVolcanicRadius() * 2,
                    this.vent.fissureLength);

                if (this.vent.fissureLength >= 0) {
                    this.vent.fissureLength = Math.min(
                        targetLength,
                        this.vent.maxFissureLength);
                } else {
                        this.vent.fissureLength = targetLength;
                }
                
                if (prevFissureLength != this.vent.fissureLength) {
                    this.vent.flushCache();
                }
            }
        }
    }

    public boolean isErupting() {
        return this.erupting;
    }

    public VolcanoEruptStyle getStyle() {
        // System.out.println("[getStyle] "+this.style.lavaMultiplier+", "+this.style.bombMultiplier+", "+this.style.ashMultiplier);
        return this.style;
    }

    public void setStyle(VolcanoEruptStyle style) {
        this.style = style;
        this.vent.flushCache();

        if (this.isErupting()) {
            this.stop();
            this.start();
        }
    }

    public void startFlowingLava() {
        if (this.vent != null && this.vent.isKillSwitchActive())
            return;

        this.vent.initialize();
        this.vent.setStatus(VolcanoVentStatus.ERUPTING);
        this.vent.lavaFlow.resetThisFlow();
        this.vent.lavaFlow.settings.flowing = true;
    }

    public void stopFlowingLava() {
        if (this.vent != null && this.vent.isKillSwitchActive())
            return;

        vent.lavaFlow.settings.flowing = false;
        this.vent.setStatus((!this.vent.isExploding()) ? VolcanoVentStatus.MAJOR_ACTIVITY : this.vent.getStatus());
        this.vent.cool();

        if (this.vent.getStatus() != VolcanoVentStatus.ERUPTING) {
            this.vent.record.endEjectaTrack();
        }

        this.vent.lavadome.postConeBuildHandler();
    }

    public void startExploding() {
        this.vent.initialize();
        this.vent.setStatus(VolcanoVentStatus.ERUPTING);
        this.vent.explosion.running = true;

        if (this.vent.erupt.getStyle().canFormCaldera) {
            if (!this.vent.caldera.isSettedUp()) this.vent.caldera.autoSetup();
            this.vent.caldera.startErupt();
        }
    }

    public void stopExploding() {
        this.vent.explosion.running = false;
        this.vent.setStatus((!this.vent.isFlowingLava()) ? VolcanoVentStatus.MAJOR_ACTIVITY : this.vent.getStatus());

        if (this.vent.getStatus() != VolcanoVentStatus.ERUPTING) {
            this.vent.record.endEjectaTrack();
        }

        this.vent.lavadome.postConeBuildHandler();
    }

    public void importConfig(JSONObject json) {
        this.setStyle(VolcanoEruptStyle.getVolcanoEruptStyle((String) json.get("style")));
    }

    public JSONObject exportConfig() {
        JSONObject json = new JSONObject();
        json.put("style", this.style.toString());

        return json;
    }
}
