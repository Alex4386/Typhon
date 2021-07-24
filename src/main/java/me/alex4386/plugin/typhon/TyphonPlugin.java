package me.alex4386.plugin.typhon;

import com.comphenix.protocol.ProtocolLibrary;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombListener;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogger;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoConstructionStatus;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.parser.ParseException;
//import com.sk89q.worldedit.WorldEdit;
//import com.sk89q.worldedit.bukkit.WorldEditPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TyphonPlugin extends JavaPlugin {
    public static File dataDir;
    public static File volcanoDir = new File(TyphonPlugin.dataDir, "Volcanoes");

    public static Map<String, Volcano> listVolcanoes = new HashMap<String, Volcano>();
    public static List<VolcanoConstructionStatus> constructionStatuses = new ArrayList<>();
    public static VolcanoLogger logger = new VolcanoLogger();

    public static Plugin plugin;
    public static VolcanoBombListener vbl;

    public static int minecraftTicksPerSeconds = 20;

    public static String version = "";

    @Override
    public void onEnable() {
        plugin = this;
        version = this.getDescription().getVersion();

        // Plugin startup logic
        logger.log(VolcanoLogClass.INIT, "Initializing...");

        TyphonPlugin.dataDir = this.getDataFolder();
        TyphonPlugin.volcanoDir = new File(TyphonPlugin.dataDir, "Volcanoes");

        logger.debug(VolcanoLogClass.INIT, "Data directory detected: "+this.dataDir);

        if (!dataDir.exists()) {
            logger.debug(VolcanoLogClass.INIT, "Data directory not found, creating one...");
            dataDir.mkdir();
        }

        logger.debug(VolcanoLogClass.INIT, "Volcano Data detected: "+this.volcanoDir);
        if (!this.volcanoDir.exists()) {
            logger.debug(VolcanoLogClass.INIT, "Volcano Data directory not found, creating one...");
            this.volcanoDir.mkdir();
        }

        logger.debug(VolcanoLogClass.INIT, "Presetting default config if not exists...");
        this.saveDefaultConfig();

        logger.debug(VolcanoLogClass.INIT, "Loading Volcano default config...");
        loadConfig();

        logger.debug(VolcanoLogClass.INIT, "Loading Volcanoes...");

        File[] volcanoDirs = this.volcanoDir.listFiles();
        for (File volcanoDir : volcanoDirs) {
            if (volcanoDir.isDirectory()) {
                String volcanoName = volcanoDir.getName();
                try {
                    Volcano volcano = new Volcano(volcanoDir.toPath());
                    listVolcanoes.put(volcano.name, volcano);
                } catch (IOException e) {
                    logger.error(VolcanoLogClass.INIT, "Loading volcano "+volcanoName+" at path "+volcanoDir.getPath()+" caused I/O Error!");
                } catch (ParseException e) {
                    logger.error(VolcanoLogClass.INIT, "Loading volcano "+volcanoName+" at path "+volcanoDir.getPath()+" caused Invalid JSON Error! Please turn on debug mode for verbose output!");
                }
            }
        }

        logger.debug(VolcanoLogClass.CORE, "Loaded Volcanoes!");

        logger.log(VolcanoLogClass.PLAYER_EVENT, "Initializing...");
        logger.log(VolcanoLogClass.PLAYER_EVENT, "Initialization complete!");

        logger.log(VolcanoLogClass.BOMB, "Initializing...");
        vbl = new VolcanoBombListener();
        vbl.initialize();

        logger.log(VolcanoLogClass.BOMB, "Initialization Complete!");

        logger.log(VolcanoLogClass.INIT, "Initialization Complete!");
    }

    public static void reload() {
        logger.log(VolcanoLogClass.CORE, "Reload Requested. Processing Reload...");

        logger.debug(VolcanoLogClass.CORE, "Shutting down all loaded volcanoes...");
        for (Map.Entry<String, Volcano> entry : listVolcanoes.entrySet()) {
            Volcano volcano = entry.getValue();
            volcano.shutdown();

            try {
                volcano.load();
            } catch (IOException e) {
                volcano.logger.error(VolcanoLogClass.INIT, "Reloading volcano "+volcano.name+" at path "+volcanoDir.getPath()+" caused I/O Error!");
            } catch (ParseException e) {
                volcano.logger.error(VolcanoLogClass.INIT, "Reloading volcano "+volcano.name+" at path "+volcanoDir.getPath()+" caused Invalid JSON Error! Please turn on debug mode for verbose output!");
            }

            volcano.initialize();
        }

        logger.debug(VolcanoLogClass.CORE, "Unloading Volcanoes...");
        listVolcanoes.clear();

        logger.debug(VolcanoLogClass.CORE, "Reloading Config...");
        plugin.reloadConfig();

        logger.debug(VolcanoLogClass.CORE, "Reloading Default Config...");
        loadConfig();

        logger.log(VolcanoLogClass.CORE, "Reload Complete!");
    }

    public static void loadConfig() {}

    @Override
    public void onDisable() {
        logger.debug(VolcanoLogClass.CORE, "Disabling Plugin...");

        // Plugin shutdown logic
        for (Volcano volcano : listVolcanoes.values()) {
            try {
                volcano.save();
                volcano.shutdown();
            } catch (IOException e) {
                volcano.logger.error(VolcanoLogClass.CORE, "Saving Failed! I/O Error detected!");
            }
        }

        vbl.shutdown();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return TyphonCommand.onTabComplete(sender, command, label, args);

    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return TyphonCommand.onCommand(sender, command, label, args);
    }

}
