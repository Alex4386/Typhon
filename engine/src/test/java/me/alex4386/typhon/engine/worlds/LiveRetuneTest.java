package me.alex4386.typhon.engine.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.testing.Runs;
import org.junit.jupiter.api.Test;

/**
 * Live retuning: every parameter {@link ConfigImpact} calls live is taken by the running world in place
 * (its subsystems then run exactly the new definitions), and a live change is deterministic, survives a
 * save and restore bit for bit, and does not depend on the thread count.
 */
class LiveRetuneTest {
    // ── Definition trees ──

    /** Dotted paths of the numeric and boolean leaves of a definition tree (lists are skipped). */
    @SuppressWarnings("unchecked")
    static void leaves(String prefix, Map<String, Object> tree, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) leaves(path, (Map<String, Object>) m, out);
            else if (v instanceof Number || v instanceof Boolean) out.put(path, v);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copy(Map<String, Object> tree) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v instanceof Map<?, ?> m ? copy((Map<String, Object>) m) : v instanceof List<?> l ? new ArrayList<>(l) : v);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> with(Map<String, Object> tree, String path, Object value) {
        Map<String, Object> out = copy(tree);
        Map<String, Object> node = out;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) node = (Map<String, Object>) node.get(parts[i]);
        node.put(parts[parts.length - 1], value);
        return out;
    }

    /** A different, usually valid value: ×1.5 (0 → 0.1), integers +1, booleans flipped; NaN (auto) → 1.5. */
    static Object perturb(Object v) {
        if (v instanceof Boolean b) return !b;
        if (v instanceof Integer i) return i + 1;
        if (v instanceof Long l) return l + 1;
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d)) return 1.5;
        if (Double.isInfinite(d)) return 1e6;
        return d == 0 ? 0.1 : d * 1.5;
    }

    private static void assertSane(World world) {
        for (var v : world.volcanoes().values()) {
            assertTrue(Double.isFinite(v.chamber().overpressureMPa()), v.volcanoId() + " overpressure");
            assertTrue(Double.isFinite(v.chamber().temperatureC()), v.volcanoId() + " temperature");
        }
    }

    // ── Every live parameter ──

    @Test
    void everyLiveParameterIsTakenInPlace() {
        WorldDefinition w = WorldTest.world();
        List<VolcanoDefinition> vs = WorldTest.twins();
        World world = World.create(w, vs, WorldTest.terrain(w, vs));
        world.engine().runFor(30); // east erupts, west recharges
        assertEquals(List.of(), world.configDrift(w, vs), "a fresh world runs its definitions");

        List<String> failures = new ArrayList<>();
        int applied = 0;
        int invalid = 0;

        // world parameters
        Map<String, Object> worldTree = w.toTree();
        Map<String, Object> worldLeaves = new LinkedHashMap<>();
        leaves("", worldTree, worldLeaves);
        for (Map.Entry<String, Object> leaf : worldLeaves.entrySet()) {
            if (ConfigImpact.world(leaf.getKey()).kind() != ConfigImpact.Kind.LIVE) continue;
            WorldDefinition changed;
            try {
                changed = WorldDefinition.parse(ConfigNode.root("world.yaml", with(worldTree, leaf.getKey(), perturb(leaf.getValue()))));
            } catch (RuntimeException e) {
                invalid++;
                continue;
            }
            if (tryLive(world, changed, vs, "world " + leaf.getKey(), failures)) applied++;
            world.reconfigureLive(w, vs);
        }

        // every volcano's parameters
        for (int k = 0; k < vs.size(); k++) {
            VolcanoDefinition v = vs.get(k);
            Map<String, Object> tree = v.toTree();
            Map<String, Object> volcanoLeaves = new LinkedHashMap<>();
            leaves("", tree, volcanoLeaves);
            for (Map.Entry<String, Object> leaf : volcanoLeaves.entrySet()) {
                if (ConfigImpact.volcano(leaf.getKey()).kind() != ConfigImpact.Kind.LIVE) continue;
                VolcanoDefinition changedVolcano;
                try {
                    changedVolcano = VolcanoDefinition.parse(v.id(),
                            ConfigNode.root(v.id() + ".yaml", with(tree, leaf.getKey(), perturb(leaf.getValue()))), 4);
                } catch (RuntimeException e) {
                    invalid++;
                    continue;
                }
                List<VolcanoDefinition> changed = new ArrayList<>(vs);
                changed.set(k, changedVolcano);
                if (tryLive(world, w, changed, v.id() + " " + leaf.getKey(), failures)) applied++;
                world.reconfigureLive(w, vs);
            }
        }
        assertEquals(List.of(), world.configDrift(w, vs), "reverting restores the original configuration");
        assertTrue(failures.isEmpty(), failures.size() + " live parameters failed:\n  " + String.join("\n  ", failures));
        assertTrue(applied > 150, "applied " + applied + " live changes (" + invalid + " perturbations were invalid)");
    }

    private static boolean tryLive(World world, WorldDefinition w, List<VolcanoDefinition> vs, String what, List<String> failures) {
        try {
            world.reconfigureLive(w, vs);
            List<String> drift = world.configDrift(w, vs);
            if (!drift.isEmpty()) {
                failures.add(what + ": still running the old configuration of " + drift);
                return false;
            }
            world.engine().runFor(1);
            assertSane(world);
            return true;
        } catch (RuntimeException | AssertionError e) {
            failures.add(what + ": " + e);
            return false;
        }
    }

    // ── Determinism ──

    private static WorldDefinition rainyWorld() {
        Map<String, Object> tree = WorldTest.world().toTree();
        return WorldDefinition.parse(ConfigNode.root("world.yaml", with(tree, "climate.rainfallMmPerHour", 5.0)));
    }

    private static List<VolcanoDefinition> retunedTwins() {
        List<VolcanoDefinition> out = new ArrayList<>();
        for (VolcanoDefinition v : WorldTest.twins()) {
            Map<String, Object> tree = v.toTree();
            tree = with(tree, "magma.chamber.supplyRate", 2.0);
            tree = with(tree, "magma.chamber.tensileStrengthMPa", 8.0);
            out.add(VolcanoDefinition.parse(v.id(), ConfigNode.root(v.id() + ".yaml", tree), 4));
        }
        return out;
    }

    private record Run(List<EngineFrame> after, String hash) {}

    /** 10 s, a live retune, then 10 s more; with {@code saveAfter} ≥ 0, saved and reopened that far into the second part. */
    private static Run retuned(int threads, double saveAfter) {
        String previous = System.getProperty("typhon.threads");
        System.setProperty("typhon.threads", Integer.toString(threads));
        try {
            WorldDefinition w = WorldTest.world();
            List<VolcanoDefinition> vs = WorldTest.twins();
            InMemorySaveStore state = new InMemorySaveStore();
            InMemorySaveStore history = new InMemorySaveStore();
            World world = World.create(w, vs, WorldTest.terrain(w, vs), state, history);
            world.engine().runFor(10);
            WorldDefinition w2 = rainyWorld();
            List<VolcanoDefinition> vs2 = retunedTwins();
            world.reconfigureLive(w2, vs2);
            List<EngineFrame> frames = new ArrayList<>();
            double t0 = world.engine().time(); // absolute ends: steps vary in length
            if (saveAfter >= 0) {
                frames.addAll(Runs.until(world.engine(), t0 + saveAfter));
                world.save();
                // the files now hold the retuned definitions: a strict reopen must see no change
                world = World.reopen(w2, vs2, state, history, World.ChangePolicy.REJECT);
                assertTrue(world.changes().isEmpty(), world.changes().toString());
                frames.addAll(Runs.until(world.engine(), t0 + 10));
            } else {
                frames.addAll(Runs.until(world.engine(), t0 + 10));
            }
            return new Run(frames, world.engine().stateHash());
        } finally {
            if (previous == null) System.clearProperty("typhon.threads");
            else System.setProperty("typhon.threads", previous);
        }
    }

    @Test
    void liveChangeRestoresBitForBitAndIgnoresThreadCount() {
        Run straight = retuned(1, -1);
        Run restored = retuned(1, 4);
        assertEquals(straight.hash, restored.hash, "save and restore after a live change continue exactly");
        assertEquals(straight.after, restored.after);
        Run threaded = retuned(3, -1);
        assertEquals(straight.hash, threaded.hash, "a live change does not depend on the thread count");
    }

    @Test
    void retuneActuallyChangesTheRun() {
        Run straight = retuned(1, -1);
        WorldDefinition w = WorldTest.world();
        List<VolcanoDefinition> vs = WorldTest.twins();
        World world = World.create(w, vs, WorldTest.terrain(w, vs));
        world.engine().runFor(20);
        assertTrue(!straight.hash.equals(world.engine().stateHash()), "the retuned run differs from the untouched one");
        Function<World, Double> supply = x -> x.volcano("west").chamber().supplyRate();
        World live = World.create(w, vs, WorldTest.terrain(w, vs));
        live.reconfigureLive(rainyWorld(), retunedTwins());
        assertEquals(2.0, supply.apply(live), 1e-12, "the configured supply takes over");
        assertEquals(5.0, live.definition().climate().rainfallMmPerHour(), 1e-12);
    }
}
