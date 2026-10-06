package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.expansion.ExpansionEvents;
import me.alex4386.typhon.engine.lava.LavaEvents;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.subsurface.WaterBudget;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.worlds.ConfigImpact;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end flow on the {@code island} preset, through the same path the sim-server uses
 * ({@link WorldScenarios}): create the world, check the island and its sea, the basal water lens, erupt
 * until lava reaches the sea, turn a live dial mid-eruption, and check that saving, reloading and the
 * thread count change nothing. Prints a short report. Run with {@code ./gradlew :simulator:islandFlow}.
 */
@Tag("flow")
class IslandFlowTest {
    private static final String PRESET = "island";
    private static final long SEED = 1;
    /** Simulated steps (50 ms) allowed for the lava to reach the sea. */
    private static final int MAX_ERUPTION_STEPS = 200_000;

    private final Report report = new Report();

    @Test
    void islandFlow(@TempDir Path worlds) throws Exception {
        try {
            run(worlds);
        } finally {
            report.print();
        }
    }

    private void run(Path worlds) {
        // ── 1. Create the world (as the sim-server's createSession {preset:"island"} does) ──
        long t = System.nanoTime();
        Path dir = worlds.resolve("island");
        WorldScenarios.writeFromPreset(Presets.get(PRESET), SEED, dir);
        Scenario s = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        s.engine().step(); // the initial terrain is applied on the first step
        World world = s.session();
        assertNotNull(world, "a world session");
        String vid = s.volcano().volcanoId();
        report.stage("create", t, "world '%s' from preset '%s', %d×%d columns of %.0f m", dir.getFileName(), PRESET,
                s.initialTerrain().size(), s.initialTerrain().size(), s.terrain().world().spec().metersPerColumn());

        // ── 2. Terrain: a smooth island in open sea ──
        t = System.nanoTime();
        Island island = Island.measure(s);
        assertTrue(island.onBlockTops < 0.02,
                "continuous ground, no block terraces: " + pct(island.onBlockTops) + " of columns sit on block tops");
        assertTrue(island.edgeAllSea, "sea on every side of the core");
        assertEquals(1, island.landComponents, "one island");
        assertFalse(island.landTouchesEdge, "the coastline closes inside the core");
        assertTrue(island.areaKm2 > 20 && island.areaKm2 < 40, "island area " + island.areaKm2 + " km²");
        assertTrue(island.summitM > 600 && island.summitM < 800, "summit " + island.summitM + " m");
        assertTrue(island.deepestM < -1500, "deep sea floor around it: " + island.deepestM + " m");
        report.stage("terrain", t, "island %.1f km², summit %.0f m, sea floor to %.0f m; %s on block tops; coast closed",
                island.areaKm2, island.summitM, island.deepestM, pct(island.onBlockTops));

        // ── 3. Hydrology: sea boundary and a basal freshwater lens ──
        t = System.nanoTime();
        Subsurface sub = world.subsurface();
        for (int i = 0; i < 20 * 60; i++) s.engine().step(); // a simulated minute for the solvers to settle
        WorldModel wm = s.terrain().world();
        double l = wm.spec().metersPerColumn();
        int coastX = (int) Math.floor(island.shoreRadiusM * 0.9 / l);
        int seaX = (int) Math.floor((island.shoreRadiusM + 300) / l);
        double coastHead = wm.surfaceZ(coastX, 0) - sub.waterTableDepthM(coastX, 0);
        double seaHead = wm.surfaceZ(seaX, 0) - sub.waterTableDepthM(seaX, 0);
        double summitDepth = sub.waterTableDepthM(island.summitX, island.summitZ);
        assertEquals(0, seaHead, 0.5, "the sea fixes the water table offshore");
        assertTrue(coastHead > -0.5 && coastHead < 15, "a thin lens just above sea level at the coast: " + coastHead + " m");
        assertTrue(coastHead < wm.surfaceZ(coastX, 0), "the coastal water table lies below the ground");
        assertTrue(summitDepth > 100, "the water table is deep under the summit: " + summitDepth + " m");
        WaterBudget budget = sub.budget();
        double scale = Math.max(1, Math.abs(budget.storage()));
        assertTrue(Math.abs(budget.imbalance()) <= 1e-6 * scale + 1,
                "the water budget closes before the eruption: imbalance " + budget.imbalance() + " m³ of " + budget.storage());
        report.stage("hydrology", t, "offshore head %.2f m, coastal lens %.2f m a.s.l., summit water table %.0f m deep;"
                + " budget imbalance %.3g m³ (storage %.3g m³)", seaHead, coastHead, summitDepth, budget.imbalance(), budget.storage());

        // ── 4. Eruption, with a live dial mid-way ──
        t = System.nanoTime();
        long startStep = s.engine().currentStep();
        s.engine().submit(new MagmaCommands.StartEruption(vid));
        MagmaChamber chamber = s.volcano().chamber();
        Eruption e = new Eruption(island);
        boolean dialled = false;
        double dialBalanceBefore = Double.NaN;
        double dialBalanceAfter = Double.NaN;
        double rateBefore = Double.NaN;
        String dryRun = null;
        for (int i = 0; i < MAX_ERUPTION_STEPS && e.firstOceanEntry < 0; i++) {
            EngineFrame frame = s.engine().step();
            e.observe(frame, s.engine().currentStep());
            if (!dialled && chamber.erupting() && s.engine().currentStep() - startStep > 20 * 60) {
                // turn the magma-supply dial ×4 through the live path (no rebuild, state kept)
                rateBefore = chamber.physicalEruptionRate();
                dialBalanceBefore = chamber.balanceOverpressureMPa();
                double supplyBefore = chamber.supplyRate();
                world.reconfigureLive(world.definition(), List.of(withSupply(world.volcanoDefinitions().get(0), 4 * supplyBefore)));
                s.engine().step();
                assertEquals(4 * supplyBefore, chamber.supplyRate(), 1e-9, "the dial applies at once");
                for (int k = 0; k < 40; k++) s.engine().step();
                dialBalanceAfter = chamber.balanceOverpressureMPa();
                assertTrue(dialBalanceAfter > dialBalanceBefore,
                        "more supply raises the pressure the eruption settles at: " + dialBalanceBefore + " → " + dialBalanceAfter);
                // a dry run of a change that needs a restart: classified, nothing applied
                ConfigImpact.Impact impact = ConfigImpact.volcano("magma.chamber.initialTemperatureC");
                assertEquals(ConfigImpact.Kind.REINIT, impact.kind());
                assertEquals(4 * supplyBefore, chamber.supplyRate(), 1e-9, "the classification alone changes nothing");
                dryRun = impact.kind() + " (" + impact.reason() + ")";
                dialled = true;
            }
        }
        assertTrue(e.started >= 0, "the eruption started");
        assertTrue(dialled, "the live dial was turned mid-eruption");
        assertTrue(e.firstOceanEntry >= 0, "lava reached the sea within " + MAX_ERUPTION_STEPS + " steps");
        // keep erupting a little after the first entry so the delta can build
        for (int i = 0; i < 20 * 600; i++) e.observe(s.engine().step(), s.engine().currentStep());
        e.measureCoast(s);
        assertTrue(e.lavaBelowSea + e.newLand > 0, "lava on formerly submerged ground (a delta) or below sea level");
        report.stage("eruption", t, "started at %s, first ocean entry at %s (%d entry events), %d formerly-submerged"
                        + " columns under lava, %d new land; %d expansion events (%d tiles)", time(e.started), time(e.firstOceanEntry),
                e.oceanEntries, e.lavaBelowSea, e.newLand, e.expansions, e.expandedTiles);
        report.line("live dial: supply ×4 at %.2f m³/s eruption → balance pressure %.2f → %.2f MPa; dry run of"
                + " initialTemperatureC: %s", rateBefore, dialBalanceBefore, dialBalanceAfter, dryRun);
        WaterBudget after = sub.budget();
        report.line("water budget after the eruption: imbalance %.3g m³ (storage %.3g m³)", after.imbalance(), after.storage());

        // ── 5. Save/restore mid-eruption and thread count ──
        t = System.nanoTime();
        long mid = e.started + 20 * 120; // two simulated minutes into the eruption
        int more = 20 * 60;
        Path b = worlds.resolve("island-b");
        Path c = worlds.resolve("island-c");
        WorldScenarios.writeFromPreset(Presets.get(PRESET), SEED, b);
        WorldScenarios.writeFromPreset(Presets.get(PRESET), SEED, c);
        Scenario sb = WorldScenarios.open(b, World.ChangePolicy.REJECT);
        replay(sb, vid, startStep, mid);
        sb.saveWorld();
        Scenario resumed = WorldScenarios.open(b, World.ChangePolicy.REJECT);
        for (int i = 0; i < more; i++) resumed.engine().step();
        Scenario single = WorldScenarios.open(c, World.ChangePolicy.REJECT, 1);
        replay(single, vid, startStep, mid + more);
        assertEquals(single.engine().stateHash(), resumed.engine().stateHash(),
                "saved and resumed mid-eruption (default threads) = uninterrupted on one thread");
        report.stage("restore", t, "saved at step %d (eruption running), resumed %d steps: identical to an uninterrupted"
                + " single-thread run", mid, more);
    }

    /** Steps a fresh scenario to {@code until}, starting the eruption at {@code startStep} as the main run did. */
    private static void replay(Scenario s, String vid, long startStep, long until) {
        while (s.engine().currentStep() < until) {
            if (s.engine().currentStep() == startStep) s.engine().submit(new MagmaCommands.StartEruption(vid));
            s.engine().step();
        }
    }

    /** The volcano definition with another deep magma supply rate (m³/s). */
    @SuppressWarnings("unchecked")
    private static VolcanoDefinition withSupply(VolcanoDefinition v, double supply) {
        Map<String, Object> tree = deepCopy(v.toTree());
        Map<String, Object> chamber = (Map<String, Object>) ((Map<String, Object>) tree.get("magma")).get("chamber");
        chamber.put("supplyRate", supply);
        return VolcanoDefinition.parse(v.id(), ConfigNode.root("volcanoes/" + v.id() + ".yaml", tree));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v instanceof Map<?, ?> sub ? deepCopy((Map<String, Object>) sub) : v instanceof List<?> list ? new ArrayList<>(list) : v);
        }
        return out;
    }

    private static String pct(double f) {
        return String.format(Locale.ROOT, "%.2f %%", 100 * f);
    }

    private static String time(long step) {
        return step < 0 ? "never" : String.format(Locale.ROOT, "step %d (%.1f min)", step, step * 0.05 / 60);
    }

    // ── Measurements ──

    /** Shape of the island over the core: smoothness, coastline, area and relief. */
    record Island(double onBlockTops, boolean edgeAllSea, int landComponents, boolean landTouchesEdge, double areaKm2,
            double summitM, int summitX, int summitZ, double deepestM, double shoreRadiusM, boolean[] submerged, int minX,
            int minZ, int size) {
        static Island measure(Scenario s) {
            WorldModel wm = s.terrain().world();
            ColumnGrid g = s.initialTerrain();
            int n = g.size();
            int x0 = g.minX();
            int z0 = g.minZ();
            double l = wm.spec().metersPerColumn();
            double sea = wm.spec().seaLevelZ();
            boolean[] land = new boolean[n * n];
            boolean[] submerged = new boolean[n * n];
            int onTop = 0;
            int known = 0;
            int landCount = 0;
            double summit = Double.NEGATIVE_INFINITY;
            double deepest = Double.POSITIVE_INFINITY;
            int sx = 0;
            int sz = 0;
            boolean edgeSea = true;
            boolean landEdge = false;
            double shoreSum = 0;
            int shoreCount = 0;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int x = x0 + i;
                    int z = z0 + j;
                    if (!wm.isKnown(x, z)) continue;
                    known++;
                    double zs = wm.surfaceZ(x, z);
                    double r = zs / l;
                    if (Math.abs(r - Math.rint(r)) < 1e-6) onTop++;
                    boolean isLand = zs >= sea;
                    land[j * n + i] = isLand;
                    submerged[j * n + i] = !isLand;
                    boolean edge = i == 0 || j == 0 || i == n - 1 || j == n - 1;
                    if (edge && isLand) landEdge = true;
                    if (edge && !(zs < sea && wm.waterZ(x, z) == sea)) edgeSea = false;
                    if (isLand) {
                        landCount++;
                        if (zs > summit) {
                            summit = zs;
                            sx = x;
                            sz = z;
                        }
                    }
                    deepest = Math.min(deepest, zs);
                }
            }
            // land components (4-connected) and the shoreline radius
            int components = 0;
            boolean[] seen = new boolean[n * n];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int k = 0; k < n * n; k++) {
                if (!land[k] || seen[k]) continue;
                components++;
                seen[k] = true;
                queue.add(k);
                while (!queue.isEmpty()) {
                    int c = queue.poll();
                    int i = c % n;
                    int j = c / n;
                    int[][] nb = {{i + 1, j}, {i - 1, j}, {i, j + 1}, {i, j - 1}};
                    for (int[] p : nb) {
                        if (p[0] < 0 || p[1] < 0 || p[0] >= n || p[1] >= n) continue;
                        int q = p[1] * n + p[0];
                        if (land[q] && !seen[q]) {
                            seen[q] = true;
                            queue.add(q);
                        } else if (!land[q]) {
                            shoreSum += Math.hypot((x0 + i + 0.5) * l, (z0 + j + 0.5) * l);
                            shoreCount++;
                        }
                    }
                }
            }
            return new Island(known == 0 ? 1 : (double) onTop / known, edgeSea, components, landEdge,
                    landCount * l * l / 1e6, summit, sx, sz, deepest, shoreCount == 0 ? 0 : shoreSum / shoreCount,
                    submerged, x0, z0, n);
        }

        boolean wasSubmerged(int x, int z) {
            int i = x - minX;
            int j = z - minZ;
            return i >= 0 && j >= 0 && i < size && j < size && submerged[j * size + i];
        }
    }

    /** What the eruption did: start, ocean entries, the coast it built, growth of the world. */
    static final class Eruption {
        final Island island;
        long started = -1;
        long firstOceanEntry = -1;
        int oceanEntries;
        int expansions;
        int expandedTiles;
        int lavaBelowSea;
        int newLand;

        Eruption(Island island) {
            this.island = island;
        }

        void observe(EngineFrame frame, long step) {
            for (EngineEvent ev : frame.events()) {
                if (ev instanceof me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted && started < 0) started = step;
                if (ev instanceof LavaEvents.LavaOceanEntry) {
                    oceanEntries++;
                    if (firstOceanEntry < 0) firstOceanEntry = step;
                }
                if (ev instanceof ExpansionEvents.AreaExpanded a) {
                    expansions++;
                    expandedTiles += a.tiles().size();
                }
            }
        }

        void measureCoast(Scenario s) {
            WorldModel wm = s.terrain().world();
            double sea = wm.spec().seaLevelZ();
            for (int j = 0; j < island.size; j++) {
                for (int i = 0; i < island.size; i++) {
                    int x = island.minX + i;
                    int z = island.minZ + j;
                    if (!island.wasSubmerged(x, z) || !wm.isKnown(x, z)) continue;
                    if (s.lava().thickness(x, z) > 0) lavaBelowSea++;
                    if (wm.surfaceZ(x, z) >= sea) newLand++;
                }
            }
        }
    }

    /** Timed stages and notes, printed at the end (also when an assertion fails). */
    static final class Report {
        private final List<String> lines = new ArrayList<>();
        private final long start = System.nanoTime();

        void stage(String name, long since, String fmt, Object... args) {
            lines.add(String.format(Locale.ROOT, "  %-10s %6.1f s  ", name, (System.nanoTime() - since) / 1e9)
                    + String.format(Locale.ROOT, fmt, args));
        }

        void line(String fmt, Object... args) {
            lines.add("                       " + String.format(Locale.ROOT, fmt, args));
        }

        void print() {
            System.out.println("ISLAND FLOW (" + String.format(Locale.ROOT, "%.0f s", (System.nanoTime() - start) / 1e9) + ")");
            for (String l : lines) System.out.println(l);
        }
    }
}
