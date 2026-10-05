package me.alex4386.typhon.engine.subsurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;
import org.junit.jupiter.api.Test;

class SubsurfaceSaveTest {
    private static final WorldSpec SPEC = new WorldSpec(10, 20, -5000, 0, List.of(), "basalt", "basalt", 1);

    record Built(Engine engine, Subsurface subsurface) {}

    private static SubsurfaceConfig config() {
        SubsurfaceConfig c = new SubsurfaceConfig();
        c.rainfallMmPerHour = 30;
        c.timeScale = 1000;
        c.initialWaterTableDepthM = 4;
        return c;
    }

    private static Built build(InMemorySaveStore restore) {
        WorldModel world = restore == null
                ? SubsurfaceTestWorld.build(SPEC, 20, 20, (x, z) -> 30 - 3 * Math.hypot(x - 9.5, z - 9.5))
                : new WorldModel(SPEC);
        TerrainModel terrain = new TerrainModel(world);
        Subsurface s = new Subsurface(world, config());
        s.setHeatSources("v", new SubsurfaceHeatTest.FixedSources(List.of(),
                List.of(new HeatSources.Vent(9.5, 9.5, 1e9, 20, 200))));
        Engine.Builder b = Engine.builder(3).add(terrain).add(s);
        if (restore != null) b.restore(restore);
        return new Built(b.build(), s);
    }

    @Test
    void sameSeedSameState() {
        Built a = build(null);
        Built b = build(null);
        a.engine().runFor(300);
        b.engine().runFor(300);
        assertEquals(a.engine().stateHash(), b.engine().stateHash());
    }

    @Test
    void resultsDoNotDependOnThreadCount() {
        java.util.List<java.util.Map<String, String>> states = new java.util.ArrayList<>();
        int[] threads = {1, 4};
        for (int t = 0; t < 2; t++) {
            WorldModel world = SubsurfaceTestWorld.build(SPEC, 70, 70, (x, z) -> 30 - 0.3 * Math.hypot(x - 35, z - 35));
            SubsurfaceConfig c = config();
            c.threads = threads[t];
            Subsurface s = new Subsurface(world, c);
            s.setHeatSources("v", new SubsurfaceHeatTest.FixedSources(List.of(),
                    List.of(new HeatSources.Vent(35, 35, 1e9, 40, 200))));
            Engine engine = Engine.builder(3).add(new TerrainModel(world)).add(s).build();
            engine.runFor(600);
            // The thread count is part of the config (meta.json); compare the state files only.
            java.util.Map<String, String> state = new java.util.TreeMap<>();
            Saves.save(engine).files().forEach((path, bytes) -> {
                if (path.startsWith("fields/") || path.startsWith("subsystems/")) {
                    state.put(path, java.util.Base64.getEncoder().encodeToString(bytes));
                }
            });
            states.add(state);
        }
        assertTrue(states.get(0).keySet().stream().anyMatch(p -> p.contains("subsurface")));
        assertEquals(states.get(0), states.get(1));
    }

    @Test
    void saveAndRestoreContinuesBitForBit() {
        Built reference = build(null);
        reference.engine().runFor(300);
        reference.subsurface().addWater(5, 5, 200);
        reference.engine().runFor(300);

        Built first = build(null);
        first.engine().runFor(300);
        first.subsurface().addWater(5, 5, 200);
        first.engine().runFor(120);
        InMemorySaveStore saved = Saves.save(first.engine());

        Built second = build(saved);
        second.engine().runFor(180);
        assertEquals(reference.engine().stateHash(), second.engine().stateHash());
        WaterBudget b = second.subsurface().budget();
        assertEquals(0, b.imbalance(), 1e-6 * b.inflow(), b.toString());
        assertTrue(second.subsurface().macroSteps() > 0);
    }
}
