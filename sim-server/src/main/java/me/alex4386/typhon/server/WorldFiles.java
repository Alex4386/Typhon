package me.alex4386.typhon.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.worlds.WorldDirectory;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.WorldScenarios;

/**
 * World directories under {@code --worlds-dir}: listing them, creating one from a preset, and
 * patching a definition (base step) before it is opened.
 */
final class WorldFiles {
    /** Names usable as a directory under the worlds dir. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private WorldFiles() {}

    /** One world directory, as listed in the catalog. */
    record Listing(String name, Path dir, String title, int volcanoes, boolean hasState, String error) {}

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.contains("..");
    }

    /** {@code base}, or {@code base-2}, {@code base-3}… — the first name not taken under {@code worldsDir}. */
    static String uniqueName(Path worldsDir, String base) {
        String clean = base.replaceAll("[^A-Za-z0-9_.-]", "-").replaceAll("^[^A-Za-z0-9]+", "");
        if (clean.isEmpty()) clean = "world";
        if (clean.length() > 56) clean = clean.substring(0, 56);
        String name = clean;
        for (int n = 2; Files.exists(worldsDir.resolve(name)); n++) name = clean + "-" + n;
        return name;
    }

    /** Lists every directory with a {@code world.yaml} under {@code worldsDir} (unreadable ones carry an error). */
    static List<Listing> list(Path worldsDir) {
        List<Listing> out = new ArrayList<>();
        if (!Files.isDirectory(worldsDir)) return out;
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(worldsDir)) {
            stream.filter(p -> Files.isRegularFile(p.resolve("world.yaml"))).forEach(dirs::add);
        } catch (IOException e) {
            return out;
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            try {
                WorldDirectory wd = new WorldDirectory(dir);
                WorldDefinition def = wd.readWorld();
                int volcanoes = wd.readVolcanoes().size();
                out.add(new Listing(name, dir, def.name(), volcanoes, wd.hasState(), null));
            } catch (RuntimeException e) {
                out.add(new Listing(name, dir, name, 0, false, String.valueOf(e.getMessage())));
            }
        }
        return out;
    }

    /** Writes a new world directory reproducing {@code preset}, then applies the base step. */
    static void writePresetWorld(Preset preset, long seed, Path dir, double baseStepMs) {
        WorldScenarios.writeFromPreset(preset, seed, dir);
        if (baseStepMs != 50) patchWorld(dir, false, tree -> tree.put("baseStepMs", baseStepMs));
    }

    /**
     * Rewrites {@code world.yaml} with {@code edit} applied to its tree. The result is validated by
     * parsing it before anything is written. With {@code backup}, the original file is kept once as
     * {@code world.yaml.bak} (rewriting drops hand-written comments).
     */
    static void patchWorld(Path dir, boolean backup, Consumer<Map<String, Object>> edit) {
        WorldDirectory wd = new WorldDirectory(dir);
        Path file = wd.worldFile();
        Map<String, Object> tree = wd.readWorld().toTree();
        edit.accept(tree);
        WorldDefinition.parse(ConfigNode.root("world.yaml", tree)); // validates
        try {
            String header = header(file);
            if (backup) {
                Path bak = dir.resolve("world.yaml.bak");
                if (!Files.exists(bak)) Files.copy(file, bak, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Yaml.write(file, header, tree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The leading comment block of a YAML file (kept when rewriting it). */
    static String header(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "# Typhon volcano definition (see engine/README.md, \"World definitions\").\n"
                    + "# Placed by the user: a magma chamber with nothing built yet.\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.startsWith("#")) break;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Volcano definitions of a world (for the tuning schema). */
    static List<VolcanoDefinition> volcanoes(Path dir) {
        return new WorldDirectory(dir).readVolcanoes();
    }

    /** Deletes a world directory recursively. */
    static void delete(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
