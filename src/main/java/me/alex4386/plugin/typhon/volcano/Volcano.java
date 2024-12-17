package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonBlueMapUtils;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMetamorphism;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogger;
import me.alex4386.plugin.typhon.volcano.succession.VolcanoSuccession;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoAutoStart;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentGenesis;
import org.bukkit.*;
import org.bukkit.event.Listener;
import org.codehaus.plexus.util.FileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;

public class Volcano implements Listener {
    public Path basePath;
    public String name;

    long lastSave = System.currentTimeMillis();

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

    // volcano successions
    public VolcanoSuccession succession = new VolcanoSuccession(this);

    // vents. that spew out lavas everywhere <3
    public VolcanoVent mainVent = new VolcanoVent(this);
    public Map<String, VolcanoVent> subVents = new HashMap<>();

    // default silicate
    public double silicateLevel = 0.63;

    // updateRate
    public long updateRate = 20;

    // max concurrent eruption
    public int maxEruptions = 3;

    // on volcanic field only
    public int fieldRange = 600;

    public Volcano(Path basePath) throws IOException, ParseException {
        this.name = basePath.getFileName().toString();
        this.basePath = basePath;

        if (basePath.toFile().exists()) {
            try {
                this.load();
                this.startup();
            } catch (ParseException parseException) {
                logger.error(
                        VolcanoLogClass.CORE,
                        "Unable to parse Volcano Config Dir "
                                + basePath.toString()
                                + " for Volcano "
                                + this.name
                                + ". To inspect, please enable debug mode of plugin.");
                throw parseException;
            }
        } else {
            logger.error(
                    VolcanoLogClass.CORE,
                    "Unable to find Volcano Config Dir "
                            + basePath.toString()
                            + " for Volcano "
                            + this.name
                            + ".");
            throw new FileNotFoundException(
                    "Unable to find Volcano " + this.name + " on path: " + basePath.toString());
        }
    }

    public Volcano(Path basePath, Location loc) throws IOException, ParseException {
        this.name = basePath.getFileName().toString();
        this.basePath = basePath;

        if (basePath.toFile().exists()) {
            try {
                this.load();
                this.startup();
            } catch (ParseException parseException) {
                logger.error(
                        VolcanoLogClass.CORE,
                        "Unable to parse Volcano Config Dir "
                                + basePath.toString()
                                + " for Volcano "
                                + this.name
                                + ". To inspect, please enable debug mode of plugin.");
                throw parseException;
            }
        } else {
            if (loc != null) {
                this.location = loc;
                this.mainVent.location = loc;
                logger.log(
                        VolcanoLogClass.CORE, "Typhon is creating new Volcano " + this.name + ".");
                basePath.toFile().mkdirs();

                dataLoader.setupDirectory();
                this.save(true);
                logger.log(VolcanoLogClass.CORE, "Typhon created new Volcano " + this.name + "!");
            } else {
                logger.error(
                        VolcanoLogClass.CORE,
                        "Unable to find Volcano Config Dir "
                                + basePath.toString()
                                + " for Volcano "
                                + this.name
                                + ".");
                throw new FileNotFoundException(
                        "Unable to find Volcano " + this.name + " on path: " + basePath.toString());
            }
        }
    }

    public double getTickFactor() {
        double tickFactor = 20 / ((int) this.updateRate);

        return tickFactor;
    }

    public void initialize() {
        autoStart.initialize();
        geoThermal.initialize();
        succession.initialize();
    }

    public void initializeVents() {
        this.mainVent.initialize();
        for (Map.Entry<String, VolcanoVent> entry : this.subVents.entrySet()) {
            String name = entry.getKey();
            VolcanoVent vent = entry.getValue();
            vent.name = name;
            if (vent.enabled) {
                vent.initialize();
            }
        }
    }

    public void startup() {
        logger.log(VolcanoLogClass.CORE, "Starting up Volcano sub vents...");
        this.initializeVents();

        logger.log(VolcanoLogClass.CORE, "Starting up Volcano...");
        this.initialize();

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

            if (vent.erupt.isErupting() && !vent.genesis.canEruptAgain()) {
                vent.stop();
            }
        }

        if (runQuickCool) {
            logger.log(
                    VolcanoLogClass.CORE,
                    "Running Quickcool due to shutdown... This might take a while...");
            this.quickCool();
        }

        autoStart.shutdown();
        geoThermal.shutdown();
        succession.shutdown();

        logger.log(VolcanoLogClass.CORE, "Shutdown Complete!");
    }

    public void quickCool() {
        List<VolcanoVent> vents = manager.getVents();

        for (VolcanoVent vent : vents) {
            vent.quickCool();
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

    public void addVolcanoVent(VolcanoVent vent) {
        this.subVents.put(vent.name, vent);
        this.trySave(true);

        // make sure that this vent is now displayed on bluemap
        TyphonBlueMapUtils.addVolcanoVentOnMap(vent);
    }

    public boolean trySave() {
        return this.trySave(false);
    }

    public boolean trySave(boolean force) {
        try {
            this.save(force);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void save() throws IOException {
        this.save(false);
    }

    public void save(boolean force) throws IOException {
        if (!force) {
            int timeframe = 10;
            if (System.currentTimeMillis() - lastSave < timeframe * 1000) return;
        }

        logger.log(VolcanoLogClass.CORE, "Saving...");
        this.dataLoader.setCoreConfig(this.exportConfig());
        this.dataLoader.setAutostartConfig(this.autoStart.exportConfig());
        this.dataLoader.setMainVentConfig(this.mainVent.exportConfig());

        for (Map.Entry<String, VolcanoVent> subVentEntry : this.subVents.entrySet()) {
            String name = subVentEntry.getKey();
            VolcanoVent vent = subVentEntry.getValue();

            this.dataLoader.setSubVentConfig(name, vent.exportConfig());
        }
        logger.log(VolcanoLogClass.CORE, "Save Complete.");

        lastSave = System.currentTimeMillis();
    }

    public void delete() throws IOException {
        logger.log(VolcanoLogClass.CORE, "Deleting...");

        this.shutdown();
        this.deleteFileSystem();
        TyphonPlugin.listVolcanoes.remove(this.name);
        TyphonBlueMapUtils.removeVolcanoFromMap(this);

        logger.log(VolcanoLogClass.CORE, "Delete Complete!");
    }

    private void deleteFileSystem() throws IOException {
        deleteFileSystem(this.basePath);
    }

    private static void deleteFileSystem(Path basePath) throws IOException {
        try {
            Class<?> klass = Class.forName("org.apache.commons.io.FileUtils");
            klass.getMethod("deleteDirectory", File.class).invoke(null, basePath.toFile());
        } catch(InvocationTargetException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            FileUtils.deleteDirectory(basePath.toFile());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalArgumentException |
                 IllegalAccessException e) {

            FileUtils.deleteDirectory(basePath.toFile());
        }
    }

    public void rename(String newName) throws IOException {
        logger.log(VolcanoLogClass.CORE, "Renaming...");

        Path basePath = this.basePath;
        TyphonPlugin.listVolcanoes.remove(this.name);

        File volcanoPath = TyphonPlugin.volcanoDir;

        this.name = newName;
        File dir = new File(volcanoPath.getPath(), this.name);
        dir.mkdir();

        this.basePath = dir.toPath();

        TyphonPlugin.listVolcanoes.put(newName, this);
        this.trySave();

        deleteFileSystem(basePath);

        logger.log(VolcanoLogClass.CORE, "Rename Complete!");
    }

    public void importConfig(JSONObject configData) {
        // this.status = VolcanoStatus.getStatus((String) configData.get("status"));
        this.location =
                TyphonUtils.deserializeLocationForJSON((JSONObject) configData.get("location"));
        this.isDebug = (boolean) configData.get("isDebug");
        this.updateRate = (long) configData.get("updateRate");
        this.succession.importConfig((JSONObject) configData.get("succession"));
        this.maxEruptions = (int) (long) configData.get("maxEruptions");
        this.fieldRange = (int) (long) configData.get("fieldRange");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        // configData.put("status", this.status.toString());
        configData.put("location", TyphonUtils.serializeLocationForJSON(this.location));
        configData.put("isDebug", this.isDebug);
        configData.put("updateRate", this.updateRate);
        configData.put("succession", this.succession.exportConfig());
        configData.put("maxEruptions", this.maxEruptions);
        configData.put("fieldRange", this.fieldRange);

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

    public List<VolcanoVent> getEruptingVents() {
        List<VolcanoVent> vents = this.manager.getVents();
        List<VolcanoVent> eruptingVents = new ArrayList<>();

        for (VolcanoVent vent : vents) {
            if (vent.erupt.isErupting()) {
                eruptingVents.add(vent);
            }
        }

        return eruptingVents;
    }

    public boolean isVolcanicField() {
        return this.mainVent.genesis == VolcanoVentGenesis.MONOGENETIC;
    }
}
