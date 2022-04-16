package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.ash.VolcanoAsh;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMetamorphism;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogger;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoAutoStart;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.*;
import org.apache.commons.io.FileUtils;
import org.bukkit.event.Listener;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Volcano implements Listener {
    public Path basePath;
    public String name;

    // Core data
    public Location location;

    // metadata
    // public VolcanoStatus status = VolcanoStatus.DORMANT;

    // check if debug mode is enabled for this volcano.
    public boolean isDebug = false;

    // Volcano Data Loader
    public VolcanoDataLoader dataLoader = new VolcanoDataLoader(this);

    // Volcano Logger
    public VolcanoLogger logger = new VolcanoLogger(this);

    // Volcano Manager
    public VolcanoManager manager = new VolcanoManager(this);

    // Volcano AutoStart
    public VolcanoAutoStart autoStart = new VolcanoAutoStart(this);

    // Stone Metamorphism. that uses volcano's composition controller.
    public VolcanoMetamorphism metamorphism = new VolcanoMetamorphism(this);

    // geoThermal Activities: ?
    public VolcanoGeoThermal geoThermal = new VolcanoGeoThermal(this);

    // vents. that spew out lavas everywhere <3
    public VolcanoVent mainVent = new VolcanoVent(this);
    public Map<String, VolcanoVent> subVents = new HashMap<>();

    // default silicate
    public double silicateLevel = 0.63;

    // updateRate
    public long updateRate = 20;

    public Volcano(Path basePath) throws IOException, ParseException {
        this.name = basePath.getFileName().toString();
        this.basePath = basePath;

        if (basePath.toFile().exists()) {
            try {
                this.load();
                this.startup();
            } catch(ParseException parseException) {
                logger.error(VolcanoLogClass.CORE, "Unable to parse Volcano Config Dir "+basePath.toString()+" for Volcano "+this.name+". To inspect, please enable debug mode of plugin.");
                throw parseException;
            }
        } else {
            logger.error(VolcanoLogClass.CORE, "Unable to find Volcano Config Dir "+basePath.toString()+" for Volcano "+this.name+".");
            throw new FileNotFoundException("Unable to find Volcano "+this.name+" on path: "+basePath.toString());
        }
    }

    public Volcano(Path basePath, Location loc) throws IOException, ParseException {
        this.name = basePath.getFileName().toString();
        this.basePath = basePath;

        if (basePath.toFile().exists()) {
            try {
                this.load();
                this.startup();
            } catch(ParseException parseException) {
                logger.error(VolcanoLogClass.CORE, "Unable to parse Volcano Config Dir "+basePath.toString()+" for Volcano "+this.name+". To inspect, please enable debug mode of plugin.");
                throw parseException;
            }
        } else {
            if (loc != null) {
                this.location = loc;
                this.mainVent.location = loc;
                logger.log(VolcanoLogClass.CORE, "Typhon is creating new Volcano "+this.name+".");
                basePath.toFile().mkdirs();

                dataLoader.setupDirectory();
                this.save();
                logger.log(VolcanoLogClass.CORE, "Typhon created new Volcano "+this.name+"!");
            } else {
                logger.error(VolcanoLogClass.CORE, "Unable to find Volcano Config Dir "+basePath.toString()+" for Volcano "+this.name+".");
                throw new FileNotFoundException("Unable to find Volcano "+this.name+" on path: "+basePath.toString());
            }
        }
    }

    public void initialize() {
        autoStart.initialize();
        geoThermal.initialize();
    }

    public void startup() {
        logger.log(VolcanoLogClass.CORE, "Starting up Volcano...");
        this.initialize();
        this.mainVent.initialize();
        for (Map.Entry<String, VolcanoVent> entry : this.subVents.entrySet()) {
            String name = entry.getKey();
            VolcanoVent vent = entry.getValue();
            vent.name = name;
            if (vent.enabled) {
                vent.initialize();
            }
        }

        logger.log(VolcanoLogClass.CORE, "Started up!");
    }

    public void shutdown() {
        shutdown(true);
    }

    public void shutdown(boolean runQuickCool) {
        logger.log(VolcanoLogClass.CORE, "Shutting down Volcano...");
        List<VolcanoVent> vents = manager.getVents();

        for (VolcanoVent vent : vents) {
            vent.shutdown();
        }

        if (runQuickCool) {
            logger.log(VolcanoLogClass.CORE, "Running Quickcool due to shutdown... This might take a while...");
            this.quickCool();
        }

        autoStart.shutdown();
        geoThermal.shutdown();

        logger.log(VolcanoLogClass.CORE, "Shutdown Complete!");
    }

    public void quickCool() {
        List<VolcanoVent> vents = manager.getVents();

        for (VolcanoVent vent : vents) {
            vent.lavaFlow.cooldownAll();
            vent.bombs.shutdown();
        }
    }

    public void load() throws IOException, ParseException {
        this.name = basePath.getFileName().toString();

        JSONObject autoStartConfig = this.dataLoader.getAutostartConfig();
        this.autoStart.importConfig(autoStartConfig);

        JSONObject coreConfig = this.dataLoader.getCoreConfig();
        this.importConfig(coreConfig);

        JSONObject mainVentConfig = this.dataLoader.getMainVentConfig();
        this.mainVent.importConfig(mainVentConfig);

        Map<String, JSONObject> subVentConfigs = this.dataLoader.getSubVentConfigs();
        for (Map.Entry<String, JSONObject> subVentConfigEntry : subVentConfigs.entrySet()) {
            String ventName = subVentConfigEntry.getKey();
            JSONObject ventConfig = subVentConfigEntry.getValue();

            subVents.put(ventName, new VolcanoVent(this, ventConfig));
            subVents.get(ventName).name = ventName;
        }
    }

    public boolean trySave() {
        try {
            this.save();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void save() throws IOException {
        logger.log(VolcanoLogClass.CORE, "Saving...");
        this.dataLoader.setCoreConfig(this.exportConfig());
        this.dataLoader.setAutostartConfig(this.autoStart.exportConfig());
        this.dataLoader.setMainVentConfig(this.mainVent.exportConfig());

        for (Map.Entry<String, VolcanoVent> subVentEntry: this.subVents.entrySet() ) {
            String name = subVentEntry.getKey();
            VolcanoVent vent = subVentEntry.getValue();

            this.dataLoader.setSubVentConfig(name, vent.exportConfig());
        }
        logger.log(VolcanoLogClass.CORE, "Save Complete.");
    }

    public void delete() throws IOException {
        logger.log(VolcanoLogClass.CORE, "Deleting...");

        this.shutdown();
        FileUtils.deleteDirectory(this.basePath.toFile());
        TyphonPlugin.listVolcanoes.remove(this.name);

        logger.log(VolcanoLogClass.CORE, "Delete Complete!");
    }

    public void importConfig(JSONObject configData) {
        //this.status = VolcanoStatus.getStatus((String) configData.get("status"));
        this.location = TyphonUtils.deserializeLocationForJSON((JSONObject) configData.get("location"));
        this.isDebug = (boolean) configData.get("isDebug");
        this.updateRate = (long) configData.get("updateRate");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        //configData.put("status", this.status.toString());
        configData.put("location", TyphonUtils.serializeLocationForJSON(this.location));
        configData.put("isDebug", this.isDebug);
        configData.put("updateRate", this.updateRate);

        return configData;
    }

    @Deprecated
    // Erupt to default vent.
    public void start() {
        initialize();
        this.mainVent.start();
    }

    @Deprecated
    // Stop the entire volcano.
    public void stop() {
        List<VolcanoVent> startedVents = manager.currentlyStartedVents();
        for (VolcanoVent vent : startedVents) {
            vent.stop();
        }
    }
}
