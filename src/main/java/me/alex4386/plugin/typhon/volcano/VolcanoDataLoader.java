package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.IOException;
import java.nio.file.NotDirectoryException;
import java.util.HashMap;
import java.util.Map;

public class VolcanoDataLoader {
    public Volcano volcano;

    public static String coreFilename = "core.json";
    public static String autoStartFilename = "autostart.json";
    public static String mainVentFilename = "mainVent.json";

    public static String ventsDirname = "vents";

    VolcanoDataLoader(Volcano volcano) {
        this.volcano = volcano;
    }

    public File getFile(String filename) {
        return new File(this.volcano.basePath.toString(), filename);
    }

    public JSONObject getCoreConfig() throws IOException, ParseException {
        File file = this.getFile(coreFilename);
        return TyphonUtils.parseJSON(file);
    }

    public JSONObject getAutostartConfig() throws IOException, ParseException {
        File file = this.getFile(autoStartFilename);
        return TyphonUtils.parseJSON(file);
    }

    public JSONObject getMainVentConfig() throws IOException, ParseException {
        File file = this.getFile(mainVentFilename);
        return TyphonUtils.parseJSON(file);
    }

    public void setupDirectory() {
        File ventDir = this.getFile(ventsDirname);

        ventDir.mkdirs();
    }

    public File[] getDirectoryFiles(String dirname) throws IOException {
        File dir = this.getFile(dirname);
        Map<String, JSONObject> map = new HashMap<>();

        if (!dir.isDirectory()) throw new NotDirectoryException(dir.getAbsolutePath()+" is not a directory!");

        return dir.listFiles();
    }

    public Map<String, JSONObject> getJSONsFromDirectoryToMap(String dirname) throws IOException, ParseException {
        Map<String, JSONObject> map = new HashMap<>();

        for (File file: getDirectoryFiles(dirname)) {
            String fileName = file.getName();
            if (fileName.toLowerCase().endsWith(".json")) {
                int idx = fileName.lastIndexOf(".");
                String ventName = fileName.substring(0, idx);

                JSONObject ventConfig = TyphonUtils.parseJSON(file);
                map.put(ventName, ventConfig);
            }
        }

        return map;
    }

    public void deleteJSONFromDirectory(String dirname, String filename) {
        File dir = this.getFile(dirname);
        File config = new File(dir.getPath(), filename+".json");
        config.delete();
    }

    public Map<String, JSONObject> getSubVentConfigs() throws IOException, ParseException {
        return this.getJSONsFromDirectoryToMap(ventsDirname);
    }

    public void deleteSubVentConfig(String string) {
        this.deleteJSONFromDirectory(ventsDirname, string);
    }
    public void setCoreConfig(JSONObject jsonObject) throws IOException {
        File file = this.getFile(coreFilename);
        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setAutostartConfig(JSONObject jsonObject) throws IOException {
        File file = this.getFile(autoStartFilename);
        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setMainVentConfig(JSONObject jsonObject) throws IOException {
        File file = this.getFile(mainVentFilename);
        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setSubDirectoryJSONFile(String dirname, String filename, JSONObject jsonObject) throws IOException {
        File dir = this.getFile(dirname);
        File file = new File(dir.getPath(), filename+".json");

        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setSubVentConfig(String name, JSONObject jsonObject) throws IOException {
        setSubDirectoryJSONFile(ventsDirname, name, jsonObject);
    }
}