package me.alex4386.plugin.typhon.volcano.erupt;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.json.simple.JSONObject;

public class VolcanoErupt {
    VolcanoVent vent;
    VolcanoEruptStyle style = VolcanoEruptStyle.STROMBOLIAN;

    private boolean erupting = false;

    public VolcanoErupt(VolcanoVent vent) {
        this.vent = vent;
    }

    public void start() {
        if (this.erupting)
            return;

        if (this.style.flowsLava()) {
            this.startFlowingLava();
        }

        if (this.style.isExplosive()) {
            this.startExploding();
        }

        this.erupting = true;
    }

    public void stop() {
        this.stopExploding();
        this.stopFlowingLava();

        this.erupting = false;
    }

    public void autoConfig() {
        if (this.style == VolcanoEruptStyle.HAWAIIAN) {
            this.vent.lavaFlow.settings.silicateLevel = 0.45 + (Math.random() * (0.52 - 0.45));
            this.vent.lavaFlow.settings.flowed = 10;
            this.vent.lavaFlow.settings.delayFlowed = 14;

            this.vent.explosion.settings.minBombCount = 0;
            this.vent.explosion.settings.maxBombCount = 5;

        } else {
            this.vent.explosion.enabled = true;
            if (this.style == VolcanoEruptStyle.STROMBOLIAN
                    || this.style == VolcanoEruptStyle.VULCANIAN) {
                this.vent.lavaFlow.settings.delayFlowed = 10;
                this.vent.lavaFlow.settings.flowed = 7;

                this.vent.explosion.settings.minBombCount = 10;
                this.vent.explosion.settings.maxBombCount = 20;

                if (this.style == VolcanoEruptStyle.STROMBOLIAN) {
                    this.vent.lavaFlow.settings.silicateLevel = 0.49 + (Math.random() * (0.55 - 0.49));

                    // little bit of ash plume (particle falling)
                } else if (this.style == VolcanoEruptStyle.VULCANIAN) {
                    this.vent.lavaFlow.settings.silicateLevel = 0.54 + (Math.random() * (0.57 - 0.54));
                    this.vent.lavaFlow.settings.delayFlowed = 10;
                    this.vent.lavaFlow.settings.flowed = 7;

                    this.vent.explosion.settings.minBombCount = 50;
                    this.vent.explosion.settings.maxBombCount = 100;

                    // cloud of ash plume (campfire smoke + particle falling)
                } else {
                }
            } else if (this.style == VolcanoEruptStyle.PELEAN) {
                // requires build up of lava dome before hand

            } else {
            }
        }
    }

    public void updateVentConfig() {
        if (this.vent == null)
            return;

        if (this.style == VolcanoEruptStyle.HAWAIIAN
                || this.style == VolcanoEruptStyle.STROMBOLIAN) {
            if (this.vent.getType() == VolcanoVentType.FISSURE) {
                int prevFissureLength = this.vent.fissureLength;
                this.vent.fissureLength = (int) Math.min(
                        Math.max(
                                this.vent.longestNormalLavaFlowLength * 2,
                                this.vent.fissureLength),
                        this.vent.maxFissureLength);
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
        this.vent.initialize();
        this.vent.status = VolcanoVentStatus.ERUPTING;
        this.vent.lavaFlow.settings.flowing = true;
    }

    public void stopFlowingLava() {
        vent.lavaFlow.settings.flowing = false;
        this.vent.status = (!this.vent.isExploding()) ? VolcanoVentStatus.MAJOR_ACTIVITY : this.vent.status;
        this.vent.cool();

        if (this.vent.status != VolcanoVentStatus.ERUPTING) {
            this.vent.record.endEjectaTrack();
        }

        this.vent.lavadome.postConeBuildHandler();
    }

    public void startExploding() {
        this.vent.initialize();
        this.vent.status = VolcanoVentStatus.ERUPTING;
        this.vent.explosion.running = true;
    }

    public void stopExploding() {
        this.vent.explosion.running = false;
        this.vent.status = (!this.vent.isFlowingLava()) ? VolcanoVentStatus.MAJOR_ACTIVITY : this.vent.status;

        if (this.vent.status != VolcanoVentStatus.ERUPTING) {
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
