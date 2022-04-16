package me.alex4386.plugin.typhon.volcano.erupt;

import org.json.simple.JSONObject;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;

public class VolcanoErupt {
    VolcanoVent vent;
    VolcanoEruptStyle style = VolcanoEruptStyle.STROMBOLIAN;

    private boolean erupting = false;

    public VolcanoErupt(VolcanoVent vent) {
        this.vent = vent;
    }

    public void start() {
        if (this.erupting) return;
        
        if (this.style.flowLava) {
            this.startFlowingLava();
        }
        this.startExploding();

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
            this.vent.lavaFlow.settings.flowed = 7;
            this.vent.explosion.enabled = false;

        } else {
            this.vent.explosion.enabled = true;
            if (this.style == VolcanoEruptStyle.STROMBOLIAN || this.style == VolcanoEruptStyle.VULCANIAN) {
                this.vent.lavaFlow.settings.delayFlowed = 10;
                this.vent.lavaFlow.settings.flowed = 7;

                this.vent.explosion.settings.minBombCount = 100;
                this.vent.explosion.settings.maxBombCount = 500;
                
        
                if (this.style == VolcanoEruptStyle.STROMBOLIAN) {
                    this.vent.lavaFlow.settings.silicateLevel = 0.49 + (Math.random() * (0.55 - 0.49));
                    
                    // little bit of ash plume (particle falling)
                } else if (this.style == VolcanoEruptStyle.VULCANIAN) {
                    this.vent.lavaFlow.settings.silicateLevel = 0.54 + (Math.random() * (0.57 - 0.54));
                    this.vent.lavaFlow.settings.delayFlowed = 10;
                    this.vent.lavaFlow.settings.flowed = 7;

                    this.vent.explosion.settings.minBombCount = 500;
                    this.vent.explosion.settings.maxBombCount = 2500;
        
                    // cloud of ash plume (campfire smoke + particle falling)
                } else {}
            } else if (this.style == VolcanoEruptStyle.PELEAN) {
                // requires build up of lava dome before hand
                
            } else {}
        }
    }

    public double bombMultiplier() {
        if (this.style == VolcanoEruptStyle.STROMBOLIAN) return 1;
        else if (this.style == VolcanoEruptStyle.VULCANIAN) return 1.3;
        else if (this.style == VolcanoEruptStyle.PELEAN) return 2;
        return -1;
    }

    public boolean isErupting() {
        return this.erupting;
    }

    public VolcanoEruptStyle getStyle() {
        return this.style;
    }

    public void setStyle(VolcanoEruptStyle style) {
        this.style = style;
        this.vent.flushCache();
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
    }

    public void importConfig(JSONObject json) {
        this.style = VolcanoEruptStyle.getVolcanoEruptStyle((String) json.get("style"));
    }

    public JSONObject exportConfig() {
        JSONObject json = new JSONObject();
        json.put("style", this.style.toString());

        return json;
    }
    
}
