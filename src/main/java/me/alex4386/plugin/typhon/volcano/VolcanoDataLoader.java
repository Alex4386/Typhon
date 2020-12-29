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
    public static String compositionsFilename = "compositions.json";
    public static String mainCraterFilename = "mainCrater.json";

    public static String cratersDirname = "craters";
    public static String dikesDirname = "dikes";
    public static String magmaChambersDirname = "magmas";

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

    public JSONObject getCompositionConfig() throws IOException, ParseException {
        File file = this.getFile(compositionsFilename);
        return TyphonUtils.parseJSON(file);
    }

    public JSONObject getMainCraterConfig() throws IOException, ParseException {
        File file = this.getFile(mainCraterFilename);
        return TyphonUtils.parseJSON(file);
    }

    public void setupDirectory() {
        File craterDir = this.getFile(cratersDirname);
        File dikeDir = this.getFile(dikesDirname);
        File magmaDir = this.getFile(magmaChambersDirname);

        craterDir.mkdirs();
        dikeDir.mkdirs();
        magmaDir.mkdirs();
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
                String craterName = fileName.substring(0, idx);

                JSONObject craterConfig = TyphonUtils.parseJSON(file);
                map.put(craterName, craterConfig);
            }
        }

        return map;
    }

    public void deleteJSONFromDirectory(String dirname, String filename) {
        File dir = this.getFile(dirname);
        File config = new File(dir.getPath(), filename+".json");
        config.delete();
    }

    public Map<String, JSONObject> getSubCraterConfigs() throws IOException, ParseException {
        return this.getJSONsFromDirectoryToMap(cratersDirname);
    }

    public void deleteSubCraterConfig(String string) {
        this.deleteJSONFromDirectory(cratersDirname, string);
    }

    public Map<String, JSONObject> getDikesConfigs() throws IOException, ParseException {
        return this.getJSONsFromDirectoryToMap(dikesDirname);
    }

    public void deleteDikeConfig(String string) {
        this.deleteJSONFromDirectory(dikesDirname, string);
    }

    public Map<String, JSONObject> getMagmaChambersConfigs() throws IOException, ParseException {
        return this.getJSONsFromDirectoryToMap(magmaChambersDirname);
    }

    public void deleteMagmaChambersConfig(String string) {
        this.deleteJSONFromDirectory(magmaChambersDirname, string);
    }

    public void setCoreConfig(JSONObject jsonObject) throws IOException {
        File file = this.getFile(coreFilename);
        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setAutostartConfig(JSONObject jsonObject) throws IOException {
        File file = this.getFile(autoStartFilename);
        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setCompositionConfig(JSONObject jsonObject) throws IOException {
        File file = this.getFile(compositionsFilename);
        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setMainCraterConfig(JSONObject jsonObject) throws IOException {
        File file = this.getFile(mainCraterFilename);
        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setSubDirectoryJSONFile(String dirname, String filename, JSONObject jsonObject) throws IOException {
        File dir = this.getFile(dirname);
        File file = new File(dir.getPath(), filename+".json");

        TyphonUtils.writeJSON(file, jsonObject);
    }

    public void setSubCraterConfig(String name, JSONObject jsonObject) throws IOException {
        setSubDirectoryJSONFile(cratersDirname, name, jsonObject);
    }

    public void setDikeConfig(String name, JSONObject jsonObject) throws IOException {
        setSubDirectoryJSONFile(dikesDirname, name, jsonObject);
    }

    public void setMagmaChamberConfig(String name, JSONObject jsonObject) throws IOException {
        setSubDirectoryJSONFile(magmaChambersDirname, name, jsonObject);
    }
}