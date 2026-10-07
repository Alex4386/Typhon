package me.alex4386.typhon.engine.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.expansion.ExpansionEvents.AreaExpanded;
import me.alex4386.typhon.engine.lava.LavaSource;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.terrain.GroundColumn;
import me.alex4386.typhon.engine.terrain.GroundImport;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.terrain.TerrainGenerator;
import org.junit.jupiter.api.Test;

/**
 * On-demand growth of the simulated area: activity near the edge materialises generated ground, the run
 * stays deterministic for any thread count and across a save, and the caps hold.
 */
class WorldExpansionTest {
    /** A plane dipping east at 1/16 (4 m columns): lava from x = 24 runs off the core's east edge (x = 31). */
    static final TerrainGenerator PLANE = (x, z) -> GroundColumn.dry(x, z, 244 - (x + 0.5) * 4 / 16.0, TestGround.ROCK);

    static WorldDefinition world(String expansion) {
        return WorldDefinition.parse(Yaml.parse("world.yaml", """
                name: grow
                seed: 3
                grid: {metersPerColumn: 4}
                terrain: {source: test}
                expansion: %s
                """.formatted(expansion)));
    }

    /** The spans below were written for lava running ×20 faster than the clock; one clock now. */
    static final double LAVA = 20;

    static final String GROW = "{tileColumns: 16, marginTiles: 1, maxExtentM: 2000, maxTiles: 40}";

    static List<VolcanoDefinition> quiet() {
        return List.of(VolcanoDefinition.parse("west", Yaml.parse("west.yaml", """
                vents: [{id: summit, kind: crater, x: -78, y: 249, z: 2, radiusM: 12}]
                magma:
                  chamber: {center: {x: -78, y: -3000, z: 2}, volume: 1.0e9, initialOverpressureMPa: 1, supplyRate: 0,
                            supplyVariability: 0}
                geothermal: {radiusM: 64}
                """)));
    }

    /** The core: 64 × 64 columns (x, z in [−32, 32)) from the same generator. */
    static GroundImport core() {
        List<GroundColumn> columns = new ArrayList<>();
        for (int x = -32; x < 32; x++) {
            for (int z = -32; z < 32; z++) columns.add(PLANE.column(x, z));
        }
        return new GroundImport(columns);
    }

    static World create(String expansion, InMemorySaveStore state, InMemorySaveStore history) {
        World w = World.create(world(expansion), quiet(), core(), state, history);
        w.setTerrainGenerator(PLANE);
        w.lava().addSource(LavaSource.at("test/vent", new ColumnIndex(24, 0), 1, 1150, 50, 0.1));
        return w;
    }

    static List<AreaExpanded> expansions(List<EngineFrame> frames) {
        List<AreaExpanded> out = new ArrayList<>();
        for (EngineFrame f : frames) for (EngineEvent e : f.events()) if (e instanceof AreaExpanded a) out.add(a);
        return out;
    }

    static List<EngineFrame> withThreads(int threads, java.util.function.Supplier<List<EngineFrame>> run) {
        String previous = System.getProperty("typhon.threads");
        System.setProperty("typhon.threads", Integer.toString(threads));
        try {
            return run.get();
        } finally {
            if (previous == null) System.clearProperty("typhon.threads");
            else System.setProperty("typhon.threads", previous);
        }
    }

    @Test
    void lavaReachingTheEdgeKeepsFlowingOnMaterialisedGround() {
        World w = create(GROW, new InMemorySaveStore(), new InMemorySaveStore());
        List<EngineFrame> frames = w.engine().runFor(LAVA * 240);
        List<AreaExpanded> grown = expansions(frames);
        assertFalse(grown.isEmpty(), "the flow near the east edge materialises ground beyond it");
        assertTrue(w.terrain().world().isKnown(40, 0), "ground east of the old edge is simulated");
        assertFalse(w.terrain().world().isKnown(-80, 0), "quiet ground far west is not");
        double beyond = 0;
        for (int x = 32; x < 96; x++) for (int z = -16; z < 16; z++) beyond += w.lava().thickness(x, z);
        assertTrue(beyond > 0, "lava flows on the new ground");
        // generated columns the flow did not reach match the generator (stratigraphy built like the core's);
        // under the flow its chilled base has frozen onto the ground
        assertEquals(PLANE.column(40, 14).surfaceZ(), w.terrain().world().surfaceZ(40, 14), 1e-9);
        assertEquals(grown.get(grown.size() - 1).addedTiles(), w.expansion().addedTiles());
    }

    @Test
    void growthIsThreadInvariant() {
        List<EngineFrame> one = withThreads(1,
                () -> create(GROW, new InMemorySaveStore(), new InMemorySaveStore()).engine().runFor(LAVA * 150));
        List<EngineFrame> four = withThreads(4,
                () -> create(GROW, new InMemorySaveStore(), new InMemorySaveStore()).engine().runFor(LAVA * 150));
        assertFalse(expansions(one).isEmpty());
        assertEquals(one, four);
    }

    @Test
    void restoringMidExpansionContinuesBitForBit() {
        World reference = create(GROW, new InMemorySaveStore(), new InMemorySaveStore());
        List<EngineFrame> first = reference.engine().runFor(LAVA * 15);
        assertFalse(expansions(first).isEmpty(), "the save point lies after the first growth");
        List<EngineFrame> rest = reference.engine().runFor(LAVA * 75); // the flow next widens the area after ~LAVA·60
        assertFalse(expansions(rest).isEmpty(), "and the area keeps growing after it");

        InMemorySaveStore state = new InMemorySaveStore();
        InMemorySaveStore history = new InMemorySaveStore();
        World firstPart = create(GROW, state, history);
        firstPart.engine().runFor(LAVA * 15);
        firstPart.save();
        World resumed = World.reopen(world(GROW), quiet(), state, history, World.ChangePolicy.REJECT);
        resumed.setTerrainGenerator(PLANE);
        assertEquals(firstPart.expansion().addedTiles(), resumed.expansion().addedTiles());
        assertEquals(rest, resumed.engine().runFor(LAVA * 75));
        assertEquals(reference.engine().stateHash(), resumed.engine().stateHash());
    }

    @Test
    void capsBoundTheGrowth() {
        World capped = create("{tileColumns: 16, marginTiles: 1, maxExtentM: 2000, maxTiles: 2}",
                new InMemorySaveStore(), new InMemorySaveStore());
        capped.engine().runFor(LAVA * 240);
        assertEquals(2, capped.expansion().addedTiles());

        // a 320 m square around the origin is exactly the 64-column core: nothing to add
        World boxed = create("{tileColumns: 16, marginTiles: 1, maxExtentM: 256, maxTiles: 40}",
                new InMemorySaveStore(), new InMemorySaveStore());
        boxed.engine().runFor(LAVA * 240);
        assertEquals(0, boxed.expansion().addedTiles());
        assertFalse(boxed.terrain().world().isKnown(40, 0));
    }

    @Test
    void newGroundEntersTheWaterBudgetAsInitialStorage() {
        // no lava (its deposits move the aquifer's floor, a separate budget term): growth forced by a test source
        World w = World.create(world(GROW), quiet(), core());
        w.setTerrainGenerator(PLANE);
        w.engine().runFor(10);
        var before = w.subsurface().budget();
        w.expansion().addSource(sink -> sink.active(40, 0));
        w.engine().runFor(10);
        w.engine().step(); // a long quiet step may end with the growth: the solver takes the new columns next
        var after = w.subsurface().budget();
        assertTrue(w.expansion().addedTiles() > 0);
        assertTrue(w.subsurface().known(40, 0), "the solver initialised the new columns");
        assertTrue(after.initialGroundwater() > before.initialGroundwater(), "materialised columns bring their aquifer");
        assertEquals(before.imbalance(), after.imbalance(), 1e-9 * after.inflow() + 1e-3,
                "growth adds storage and its initial volume together");
    }

    @Test
    void expansionSettingsAreHotIncludingOnWorldsSavedWithoutThem() {
        // a world saved before expansion existed diffs the whole section ("expansion: null -> {...}")
        assertEquals(ConfigChanges.Kind.HOT, ConfigChanges.worldKind("expansion"));
        assertEquals(ConfigChanges.Kind.HOT, ConfigChanges.worldKind("expansion.maxTiles"));
    }

    @Test
    void withoutAGeneratorTheAreaStaysPut() {
        World w = World.create(world(GROW), quiet(), core());
        w.lava().addSource(LavaSource.at("test/vent", new ColumnIndex(24, 0), 1, 1150, 50, 0.1));
        assertNotNull(w.expansion());
        w.engine().runFor(LAVA * 120);
        assertEquals(0, w.expansion().addedTiles());
    }
}
