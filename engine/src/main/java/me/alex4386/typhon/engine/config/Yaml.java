package me.alex4386.typhon.engine.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

/**
 * YAML 1.2 reading and writing through SnakeYAML Engine: plain data only (mappings, lists,
 * scalars; no tag-driven object construction), duplicate keys rejected.
 */
public final class Yaml {
    private Yaml() {}

    /** Parses {@code text}; {@code name} is used in error messages. */
    public static ConfigNode parse(String name, String text) {
        LoadSettings settings = LoadSettings.builder().setLabel(name).setAllowDuplicateKeys(false).build();
        try {
            return ConfigNode.root(name, new Load(settings).loadFromString(text));
        } catch (YamlEngineException e) {
            throw new ConfigException(name + ": invalid YAML: " + e.getMessage(), e);
        }
    }

    public static ConfigNode read(Path file, String name) {
        try {
            return parse(name, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException(name + ": cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Block-style YAML for a tree of maps, lists and scalars (key order preserved). */
    public static String dump(Map<String, Object> tree) {
        DumpSettings settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2).build();
        return new Dump(settings).dumpToString(tree);
    }

    public static void write(Path file, String header, Map<String, Object> tree) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            String text = (header == null || header.isEmpty() ? "" : header.endsWith("\n") ? header : header + "\n") + dump(tree);
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
