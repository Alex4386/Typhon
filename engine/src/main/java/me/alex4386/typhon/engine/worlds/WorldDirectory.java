package me.alex4386.typhon.engine.worlds;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import me.alex4386.typhon.engine.config.ConfigException;
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
    public List<VolcanoDefinition> readVolcanoes() {
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
            list.add(VolcanoDefinition.parse(id, Yaml.read(file, VOLCANOES + "/" + name)));
        }
        return list;
    }

    /** Writes definitions as YAML (a template for a new world). */
    public void writeDefinitions(WorldDefinition world, List<VolcanoDefinition> volcanoes, String header) {
        Yaml.write(worldFile(), header, world.toTree());
        for (VolcanoDefinition v : volcanoes) Yaml.write(volcanoFile(v.id()), header, v.toTree());
    }
}
