package me.alex4386.typhon.engine.worlds;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import me.alex4386.typhon.engine.config.ConfigException;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.save.DirectorySaveStore;
import me.alex4386.typhon.engine.save.SaveStore;

/**
 * On-disk layout of a world:
 *
 * <pre>
 * worlds/&lt;world&gt;/
 *   world.yaml                       world definition (hand-edited)
 *   volcanoes/&lt;id&gt;.yaml              one definition per volcano (hand-edited)
 *   state/                           engine save (meta.json, subsystems/, fields/) + world.json
 *   history/world.ndjson             world-level historical events
 *   history/volcanoes/&lt;id&gt;/{eruptions,seismic,alerts,events}.ndjson
 * </pre>
 *
 * Definitions are only read; runtime changes (volcanoes added, removed or put to sleep while
 * running) are recorded in {@code state/world.json}, so the hand-written files stay untouched.
 */
public final class WorldDirectory {
    public static final String WORLD_FILE = "world.yaml";
    public static final String VOLCANOES = "volcanoes";
    public static final String STATE = "state";
    public static final String HISTORY = "history";

    private final Path root;
    /** Volcano files read with block positions (migrated on reading; rewritten in metres on the next save). */
    private final java.util.Set<String> blockPositionFiles = new java.util.TreeSet<>();

    public WorldDirectory(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path worldFile() {
        return root.resolve(WORLD_FILE);
    }

    public Path volcanoesDir() {
        return root.resolve(VOLCANOES);
    }

    public Path volcanoFile(String id) {
        return volcanoesDir().resolve(id + ".yaml");
    }

    public SaveStore stateStore() {
        return new DirectorySaveStore(root.resolve(STATE));
    }

    /** Store rooted at the world directory, used to append history logs. */
    public SaveStore historyStore() {
        return new DirectorySaveStore(root);
    }

    public boolean hasState() {
        return Files.exists(root.resolve(STATE).resolve("meta.json"));
    }

    public WorldDefinition readWorld() {
        if (!Files.exists(worldFile())) throw new ConfigException(root + ": no " + WORLD_FILE);
        return WorldDefinition.parse(Yaml.read(worldFile(), WORLD_FILE));
    }

    /** Every {@code volcanoes/*.yaml}, sorted by id. */
    /** The volcano files, for a world of {@code metersPerBlock} columns (which migrates block positions). */
    public List<VolcanoDefinition> readVolcanoes(double metersPerBlock) {
        List<VolcanoDefinition> list = new ArrayList<>();
        if (!Files.isDirectory(volcanoesDir())) return list;
        List<Path> files;
        try (Stream<Path> stream = Files.list(volcanoesDir())) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - ".yaml".length());
            ConfigNode node = Yaml.read(file, VOLCANOES + "/" + name);
            if (VolcanoDefinition.hasBlockPositions(node.peek())) blockPositionFiles.add(id);
            list.add(VolcanoDefinition.parse(id, node, metersPerBlock));
        }
        return list;
    }

    /**
     * Rewrites the volcano files read with block positions in the metre form, from their definitions (the
     * migration of {@link #readVolcanoes}); returns the ids rewritten.
     */
    public List<String> rewriteBlockPositionFiles(List<VolcanoDefinition> definitions) {
        List<String> rewritten = new ArrayList<>();
        for (VolcanoDefinition v : definitions) {
            if (!blockPositionFiles.remove(v.id())) continue;
            Yaml.write(volcanoFile(v.id()), "# positions in metres: {x east, y north, elevation} (migrated from block coordinates)",
                    v.toTree());
            rewritten.add(v.id());
        }
        return rewritten;
    }

    /** Writes definitions as YAML (a template for a new world). */
    public void writeDefinitions(WorldDefinition world, List<VolcanoDefinition> volcanoes, String header) {
        Yaml.write(worldFile(), header, world.toTree());
        for (VolcanoDefinition v : volcanoes) Yaml.write(volcanoFile(v.id()), header, v.toTree());
    }
}
