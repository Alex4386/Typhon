package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoAutoStart;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoDike;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMagmaChamber;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMagmaIntrusion;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMetamorphism;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogger;
import org.bukkit.*;
import org.bukkit.craftbukkit.libs.org.apache.commons.io.FileUtils;
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

    // lavaFlow for Bombs
    public VolcanoLavaFlow bombLavaFlow = new VolcanoLavaFlow(this);

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

    // composition controller
    public VolcanoComposition composition = new VolcanoComposition(this);

    // Stone Metamorphism. that uses volcano's composition controller.
    public VolcanoMetamorphism metamorphism = new VolcanoMetamorphism(this);

    // geoThermal Activities: ?
    public VolcanoGeoThermal geoThermal = new VolcanoGeoThermal(this);

    // craters. that spew out lavas everywhere <3
    public VolcanoCrater mainCrater = new VolcanoCrater(this);
    public Map<String, VolcanoCrater> subCraters = new HashMap<>();

    // magma intrusion
    public VolcanoMagmaIntrusion magmaIntrusion = new VolcanoMagmaIntrusion(this);

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
                this.mainCrater.location = loc;
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
        bombLavaFlow.initialize();
        autoStart.initialize();
        geoThermal.initialize();
    }

    public void startup() {
        logger.log(VolcanoLogClass.CORE, "Starting up Volcano...");
        this.initialize();
        this.mainCrater.initialize();
        for (Map.Entry<String, VolcanoCrater> entry : this.subCraters.entrySet()) {
            String name = entry.getKey();
            VolcanoCrater crater = entry.getValue();
            crater.name = name;
            if (crater.enabled) {
                crater.initialize();
            }
        }

        logger.log(VolcanoLogClass.CORE, "Started up!");
    }

    public void shutdown() {
        shutdown(true);
    }

    public void shutdown(boolean runQuickCool) {
        logger.log(VolcanoLogClass.CORE, "Shutting down Volcano...");
        List<VolcanoCrater> craters = manager.getCraters();

        for (VolcanoCrater crater : craters) {
            crater.shutdown();
        }

        if (runQuickCool) {
            logger.log(VolcanoLogClass.CORE, "Running Quickcool due to shutdown... This might take a while...");
            this.quickCool();
        }

        bombLavaFlow.shutdown();
        autoStart.shutdown();
        geoThermal.shutdown();

        logger.log(VolcanoLogClass.CORE, "Shutdown Complete!");
    }

    public void quickCool() {
        List<VolcanoCrater> craters = manager.getCraters();

        for (VolcanoCrater crater : craters) {
            crater.lavaFlow.cooldownAll();
            crater.bombs.shutdown();
        }

        bombLavaFlow.cooldownAll();
    }

    public void load() throws IOException, ParseException {
        this.name = basePath.getFileName().toString();

        JSONObject autoStartConfig = this.dataLoader.getAutostartConfig();
        this.autoStart.importConfig(autoStartConfig);

        JSONObject compositionConfig = this.dataLoader.getCompositionConfig();
        this.composition.importConfig(compositionConfig);

        JSONObject coreConfig = this.dataLoader.getCoreConfig();
        this.importConfig(coreConfig);

        JSONObject mainCraterConfig = this.dataLoader.getMainCraterConfig();
        this.mainCrater.importConfig(mainCraterConfig);

        Map<String, JSONObject> subCraterConfigs = this.dataLoader.getSubCraterConfigs();
        for (Map.Entry<String, JSONObject> subCraterConfigEntry : subCraterConfigs.entrySet()) {
            String craterName = subCraterConfigEntry.getKey();
            JSONObject craterConfig = subCraterConfigEntry.getValue();

            subCraters.put(craterName, new VolcanoCrater(this, craterConfig));
            subCraters.get(craterName).name = craterName;
        }

        Map<String, JSONObject> dikesConfigs = this.dataLoader.getDikesConfigs();
        for (Map.Entry<String, JSONObject> dikeConfigEntry : dikesConfigs.entrySet()) {
            String dikeName = dikeConfigEntry.getKey();
            JSONObject dikeConfig = dikeConfigEntry.getValue();

            this.magmaIntrusion.dikes.put(dikeName, new VolcanoDike(this, dikeConfig));
        }

        Map<String, JSONObject> magmaChambersConfigs = this.dataLoader.getMagmaChambersConfigs();
        for (Map.Entry<String, JSONObject> magmaChamberConfigEntry : magmaChambersConfigs.entrySet()) {
            String magmaChamberName = magmaChamberConfigEntry.getKey();
            JSONObject magmaChamberConfig = magmaChamberConfigEntry.getValue();

            this.magmaIntrusion.magmaChambers.put(magmaChamberName, new VolcanoMagmaChamber(this, magmaChamberName, magmaChamberConfig));
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
        this.dataLoader.setCompositionConfig(this.composition.exportConfig());
        this.dataLoader.setMainCraterConfig(this.mainCrater.exportConfig());

        for (Map.Entry<String, VolcanoCrater> subCraterEntry: this.subCraters.entrySet() ) {
            String name = subCraterEntry.getKey();
            VolcanoCrater crater = subCraterEntry.getValue();

            this.dataLoader.setSubCraterConfig(name, crater.exportConfig());
        }

        for (Map.Entry<String, VolcanoDike> dikeEntry: this.magmaIntrusion.dikes.entrySet() ) {
            String name = dikeEntry.getKey();
            VolcanoDike dike = dikeEntry.getValue();

            this.dataLoader.setDikeConfig(name, dike.exportConfig());
        }

        for (Map.Entry<String, VolcanoMagmaChamber> magmaChamberEntry: this.magmaIntrusion.magmaChambers.entrySet() ) {
            String name = magmaChamberEntry.getKey();
            VolcanoMagmaChamber magmaChamber = magmaChamberEntry.getValue();

            this.dataLoader.setMagmaChamberConfig(name, magmaChamber.exportConfig());
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
    // Erupt to default crater.
    public void start() {
        initialize();
        this.mainCrater.start();
    }

    @Deprecated
    // Stop the entire volcano.
    public void stop() {
        List<VolcanoCrater> startedCraters = manager.currentlyStartedCraters();
        for (VolcanoCrater crater : startedCraters) {
            crater.stop();
        }
    }
}
