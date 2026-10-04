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
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldTest {
    static final String WORLD = """
            name: twin
            seed: 7
            grid: {metersPerColumn: 4}
            terrain: {source: test}
            """;

    static String volcanoYaml(int x, double overpressure, double supply) {
        return """
                vents: [{id: summit, kind: crater, x: %d, y: 70, z: 0, radius: 3}]
                magma:
                  chamber: {center: {x: %d, y: -20, z: 0}, volume: 1.0e9, initialOverpressureMPa: %s, supplyRate: %s,
                            supplyVariability: 0}
                geothermal: {radius: 32}
                """.formatted(x, x, overpressure, supply);
    }

    static WorldDefinition world() {
        return WorldDefinition.parse(Yaml.parse("world.yaml", WORLD));
    }

    static VolcanoDefinition volcano(String id, int x, double overpressure, double supply) {
        return VolcanoDefinition.parse(id, Yaml.parse(id + ".yaml", volcanoYaml(x, overpressure, supply)));
    }

    static List<VolcanoDefinition> twins() {
        return List.of(volcano("east", 30, 14.95, 0.3), volcano("west", -30, 5, 0.3));
    }

    /** Two cones at x = ±30 rising to y = 70. */
    static TerrainSnapshot terrain(WorldDefinition world, List<VolcanoDefinition> volcanoes) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -5; cx < 5; cx++) {
            for (int cz = -4; cz < 4; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        double d = Math.min(Math.hypot(x - 30, z), Math.hypot(x + 30, z));
                        int y = (int) Math.max(40, 70 - 0.8 * d);
                        chunk.set(x, z, TerrainColumn.dry(y, BlockId.minecraft("stone")));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    static void run(World world, double seconds) {
        world.engine().runFor(seconds);
    }

    @Test
    void twoVolcanoesShareTerrainAndLava() {
        World world = World.create(world(), twins(), terrain(world(), twins()));
        assertEquals(List.of("east", "west"), List.copyOf(world.volcanoes().keySet()));
        assertEquals(4, world.worldModel().spec().metersPerColumn());
        run(world, 5);
        assertTrue(world.worldModel().isKnown(30, 0));
        assertEquals(71 * 4.0, world.worldModel().surfaceZ(30, 0), 1e-3);
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
        run(world, 60);
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

        List<VolcanoDefinition> changed = List.of(volcano("east", 30, 14.95, 0.3), volcano("west", -30, 5, 2.0));
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
        List<VolcanoDefinition> changed = new ArrayList<>(List.of(volcano("east", 30, 14.95, 0.3)));
        changed.add(VolcanoDefinition.parse("west", Yaml.parse("west.yaml",
                volcanoYaml(-30, 5, 0.3).replace("volume: 1.0e9", "volume: 5.0e9"))));

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

        world.addVolcano(volcano("north", 0, 3, 0.1));
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
        run(first, 30);
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
                Files.readString(dir.resolve("volcanoes/west.yaml")).replace("radius: 3", "radius: 5"));
        ConfigException changed = assertThrows(ConfigException.class,
                () -> World.open(dir, WorldTest::terrain, World.ChangePolicy.REJECT));
        assertTrue(changed.getMessage().contains("volcano:west vents"), changed.getMessage());
    }
}
