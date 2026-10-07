package me.alex4386.typhon.engine.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.config.ConfigException;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveFormat;
import me.alex4386.typhon.engine.terrain.GroundImport;
import me.alex4386.typhon.engine.testing.Runs;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldTest {
    static final String WORLD = """
            name: twin
            seed: 7
            grid: {metersPerColumn: 10}
            terrain: {source: test}
            """;

    /** Summit of each cone (m). */
    static final double SUMMIT_Z = 700;

    static String volcanoYaml(double x, double overpressure, double supply) {
        return """
                vents: [{id: summit, kind: crater, x: %s, y: %s, z: 5, radiusM: 30}]
                magma:
                  chamber: {center: {x: %s, y: -3000, z: 5}, volume: 1.0e9, initialOverpressureMPa: %s, supplyRate: %s,
                            supplyVariability: 0}
                geothermal: {radiusM: 300}
                """.formatted(x, SUMMIT_Z, x, overpressure, supply);
    }

    static WorldDefinition world() {
        return WorldDefinition.parse(Yaml.parse("world.yaml", WORLD));
    }

    static VolcanoDefinition volcano(String id, double x, double overpressure, double supply) {
        return VolcanoDefinition.parse(id, Yaml.parse(id + ".yaml", volcanoYaml(x, overpressure, supply)));
    }

    /** East and west vents 600 m apart, on the centres of columns 30 and −30 (10 m columns). */
    static List<VolcanoDefinition> twins() {
        return List.of(volcano("east", 305, 14.95, 0.3), volcano("west", -295, 5, 0.3));
    }

    /** Two 0.8-slope cones at x = 305 and −295 m rising from 400 m to {@link #SUMMIT_Z}, over 1.6 × 1.28 km. */
    static GroundImport terrain(WorldDefinition world, List<VolcanoDefinition> volcanoes) {
        double l = world.spec().metersPerColumn();
        return TestGround.columns(-80, -64, 79, 63, (x, z) -> {
            double px = (x + 0.5) * l;
            double pz = (z + 0.5) * l;
            double d = Math.min(Math.hypot(px - 305, pz - 5), Math.hypot(px + 295, pz - 5));
            return Math.max(400, SUMMIT_Z - 0.8 * d);
        }, TestGround.DRY);
    }

    static void run(World world, double seconds) {
        world.engine().runFor(seconds);
    }

    @Test
    void twoVolcanoesShareTerrainAndLava() {
        World world = World.create(world(), twins(), terrain(world(), twins()));
        assertEquals(List.of("east", "west"), List.copyOf(world.volcanoes().keySet()));
        assertEquals(10, world.worldModel().spec().metersPerColumn());
        run(world, 5);
        assertTrue(world.worldModel().isKnown(30, 0));
        assertEquals(SUMMIT_Z, world.worldModel().surfaceZ(30, 0), 1e-3);
        assertNotNull(world.volcano("east").chamber());
        assertNotEquals(world.volcano("east").chamber().chamberCenter(), world.volcano("west").chamber().chamberCenter());
    }

    @Test
    void savedWorldReopensBitForBitAndContinuesIdentically() {
        InMemorySaveStore state = new InMemorySaveStore();
        InMemorySaveStore history = new InMemorySaveStore();
        World original = World.create(world(), twins(), terrain(world(), twins()), state, history);
        run(original, 20);
        original.save();
        String hash = original.engine().stateHash();

        World reopened = World.reopen(world(), twins(), state, history, World.ChangePolicy.REJECT);
        assertTrue(reopened.changes().isEmpty(), reopened.changes().toString());
        assertEquals(hash, reopened.engine().stateHash());

        run(original, 15);
        run(reopened, 15);
        assertEquals(original.engine().stateHash(), reopened.engine().stateHash());
    }

    @Test
    void historyIsSplitPerVolcano() {
        InMemorySaveStore state = new InMemorySaveStore();
        InMemorySaveStore history = new InMemorySaveStore();
        World world = World.create(world(), twins(), terrain(world(), twins()), state, history);
        Runs.runPastOnset(world.engine(), 30 * 86_400, 300);
        world.save();
        assertTrue(world.volcano("east").chamber().erupting(), "east starts just below failure");
        byte[] eruptions = history.read("history/volcanoes/east/eruptions.ndjson");
        assertNotNull(eruptions, "east eruption recorded: " + history.files().keySet());
        assertTrue(new String(eruptions, StandardCharsets.UTF_8).contains("EruptionStarted"));
        assertNull(history.read("history/volcanoes/west/eruptions.ndjson"), "west stays quiet");
        assertNull(state.read(SaveFormat.HISTORY), "no combined log in a world save");
        for (String path : history.files().keySet()) {
            assertTrue(path.startsWith("history/world.ndjson") || path.startsWith("history/volcanoes/east/")
                    || path.startsWith("history/volcanoes/west/"), path);
        }
    }

    @Test
    void hotChangeToOneVolcanoIsFlaggedAndApplied() {
        InMemorySaveStore state = new InMemorySaveStore();
        InMemorySaveStore history = new InMemorySaveStore();
        World original = World.create(world(), twins(), terrain(world(), twins()), state, history);
        run(original, 5);
        original.save();

        List<VolcanoDefinition> changed = List.of(volcano("east", 305, 14.95, 0.3), volcano("west", -295, 5, 2.0));
        World reopened = World.reopen(world(), changed, state, history, World.ChangePolicy.REJECT);
        ConfigChanges changes = reopened.changes();
        assertEquals(java.util.Set.of("west"), changes.changedVolcanoes());
        assertFalse(changes.requiresReinit(), changes.toString());
        assertEquals("magma.chamber.supplyRate", changes.all().get(0).path());
        assertEquals(2.0, reopened.volcano("west").chamber().supplyRate(), 1e-12);
    }

    @Test
    void reinitChangeNeedsAPolicy() {
        InMemorySaveStore state = new InMemorySaveStore();
        InMemorySaveStore history = new InMemorySaveStore();
        World original = World.create(world(), twins(), terrain(world(), twins()), state, history);
        run(original, 30);
        original.save();
        double eastPressure = original.volcano("east").chamber().overpressureMPa();

        // West's chamber geometry changes; east is untouched.
        List<VolcanoDefinition> changed = new ArrayList<>(List.of(volcano("east", 305, 14.95, 0.3)));
        changed.add(VolcanoDefinition.parse("west", Yaml.parse("west.yaml",
                volcanoYaml(-295, 5, 0.3).replace("volume: 1.0e9", "volume: 5.0e9"))));

        ConfigException rejected = assertThrows(ConfigException.class,
                () -> World.reopen(world(), changed, state, history, World.ChangePolicy.REJECT));
        assertTrue(rejected.getMessage().contains("volcano:west magma.chamber.volume"), rejected.getMessage());

        World reset = World.reopen(world(), changed, state, history, World.ChangePolicy.RESET_CHANGED);
        assertEquals(java.util.Set.of("west"), reset.changes().reinitVolcanoes());
        assertEquals(eastPressure, reset.volcano("east").chamber().overpressureMPa(), 0, "east keeps its state");
        assertEquals(5.0, reset.volcano("west").chamber().overpressureMPa(), 1e-9, "west restarts from its definition");

        World accepted = World.reopen(world(), changed, state, history, World.ChangePolicy.ACCEPT);
        assertEquals(5.0e9, accepted.volcano("west").chamber().config().volume());
    }

    @Test
    void volcanoesCanBeAddedRemovedAndPutToSleepAtRuntime() {
        InMemorySaveStore state = new InMemorySaveStore();
        InMemorySaveStore history = new InMemorySaveStore();
        World world = World.create(world(), twins(), terrain(world(), twins()), state, history);
        run(world, 5);
        String terrainBefore = world.worldModel().column(0, 0).toString();

        world.addVolcano(volcano("north", 5, 3, 0.1));
        assertEquals(List.of("east", "north", "west"), List.copyOf(world.volcanoes().keySet()));
        assertEquals(terrainBefore, world.worldModel().column(0, 0).toString(), "terrain carried across the rebuild");
        world.setActive("west", false);
        assertEquals(0, world.volcano("west").chamber().supplyRate(), 0);
        world.removeVolcano("east");
        assertEquals(List.of("north", "west"), List.copyOf(world.volcanoes().keySet()));
        run(world, 5);
        world.save();
        assertTrue(state.list("subsystems/").stream().noneMatch(p -> p.contains("east")), state.list("subsystems/").toString());
        String hash = world.engine().stateHash();

        // The definition files still list east and west only; runtime changes come from world.json.
        World reopened = World.reopen(world(), twins(), state, history, World.ChangePolicy.REJECT);
        assertEquals(List.of("north", "west"), List.copyOf(reopened.volcanoes().keySet()));
        assertFalse(reopened.volcanoDefinitions().get(1).active());
        assertEquals(hash, reopened.engine().stateHash());
    }

    @Test
    void depositsRecordTheirVolcano() {
        World world = World.create(world(), twins(), terrain(world(), twins()));
        run(world, 1);
        int unit = world.worldModel().newUnit(new UnitRecord("east", 1, DepositType.LAVA, 1, 1150, 50));
        world.worldModel().deposit(30, 0, 2, MaterialTable.BASALT, unit);
        int top = world.worldModel().layerCount(30, 0) - 1;
        assertEquals("east", world.worldModel().unit(world.worldModel().layer(30, 0, top).unit()).volcanoId());
    }

    @Test
    void worldDirectoryRoundTrip(@TempDir Path dir) throws Exception {
        WorldDirectory layout = new WorldDirectory(dir);
        layout.writeDefinitions(world(), twins(), "# test world\n");
        assertTrue(Files.exists(dir.resolve("world.yaml")));
        assertTrue(Files.exists(dir.resolve("volcanoes/east.yaml")));

        World first = World.open(dir, WorldTest::terrain, World.ChangePolicy.REJECT);
        Runs.runPastOnset(first.engine(), 30 * 86_400, 300);
        first.save();
        assertTrue(Files.exists(dir.resolve("state/meta.json")));
        assertTrue(Files.exists(dir.resolve("state/world.json")));
        assertTrue(Files.exists(dir.resolve("history/volcanoes/east/eruptions.ndjson")));
        String hash = first.engine().stateHash();

        World second = World.open(dir, (w, v) -> {
            throw new AssertionError("a saved world must not ask for initial terrain");
        }, World.ChangePolicy.REJECT);
        assertEquals(hash, second.engine().stateHash());

        Files.writeString(dir.resolve("volcanoes/west.yaml"),
                Files.readString(dir.resolve("volcanoes/west.yaml")).replace("radiusM: 30", "radiusM: 50"));
        ConfigException changed = assertThrows(ConfigException.class,
                () -> World.open(dir, WorldTest::terrain, World.ChangePolicy.REJECT));
        assertTrue(changed.getMessage().contains("volcano:west vents"), changed.getMessage());
    }
}
