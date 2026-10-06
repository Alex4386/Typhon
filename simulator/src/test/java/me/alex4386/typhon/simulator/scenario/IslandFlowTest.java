package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.config.ChamberPlacement;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.lava.LavaEvents;
import me.alex4386.typhon.engine.magma.MagmaEvents;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.world.ReposeRelaxation;
import me.alex4386.typhon.engine.subsurface.WaterBudget;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.worlds.ConfigImpact;
import me.alex4386.typhon.engine.worlds.World;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The world-builder flow, end to end: an empty ocean, a magma chamber the user places under its sea
 * floor, and whatever the eruptions make of it. The stages of a Surtsey-like birth are asserted in
 * order (1963–67: submarine eruption, Surtseyan explosions near the surface, emergence, effusive lava
 * once the vent is sealed from the sea, lava reaching the new coast); none is chosen in advance, they
 * are read from the model. A stage that does not happen fails the flow with diagnostics. Run with
 * {@code ./gradlew :simulator:islandFlow} ({@code -Dflow.steps=N} sets the step budget).
 */
@Tag("flow")
class IslandFlowTest {
    private static final long SEED = 1;
    /** Steps (50 ms) the eruption gets to build the island. */
    private static final int BUDGET = Integer.getInteger("flow.steps", 300_000);
    /** Columns around the vent that count as the edifice. */
    private static final int R = 40;

    private final Report report = new Report();

    /** The flow's ocean: Surtsey's 130 m deep shelf; eruptions ×200 so days of activity fit a test run. */
    static WorldTemplates.Template ocean() {
        WorldTemplates.Template d = WorldTemplates.Template.defaults("ocean");
        return new WorldTemplates.Template("ocean", 2_400, 10, 130, d.elevationM(), d.slope(), 0, d.roughnessM(), 200);
    }

    /** The user's chamber: 3 km under the vent, fed at Surtsey's mean rate (~1.1 km³ in 3.5 years ≈ 10 m³/s). */
    static ChamberPlacement.Request chamber() {
        return new ChamberPlacement.Request("surtur", 0, 0, 3000, null, null, null, null, null, null, 10.0, null, null);
    }

    @Test
    void islandFromTheSeaFloor(@TempDir Path worlds) {
        try {
            run(worlds);
        } finally {
            report.print();
        }
    }

    private void run(Path worlds) {
        // ── empty world ──
        long t = System.nanoTime();
        Path dir = worlds.resolve("sea");
        WorldTemplates.writeEmpty(ocean(), "sea", SEED, dir);
        Scenario s = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        s.engine().step();
        World world = s.session();
        assertNull(s.volcano(), "an empty world");
        WorldModel wm = s.terrain().world();
        double l = wm.spec().metersPerColumn();
        double floor = wm.surfaceZ(0, 0);
        int onTops = 0;
        int known = 0;
        for (int x = -110; x < 110; x += 3) {
            for (int z = -110; z < 110; z += 3) {
                if (!wm.isKnown(x, z)) continue;
                known++;
                double zs = wm.surfaceZ(x, z);
                if (Math.abs(zs / l - Math.rint(zs / l)) < 1e-6) onTops++;
                assertTrue(zs < 0 && wm.waterZ(x, z) == 0, "open sea everywhere at the start");
            }
        }
        assertTrue(onTops < known / 20, "smooth sea floor, no terraces: " + onTops + "/" + known);
        for (int i = 0; i < 20 * 60; i++) s.engine().step();
        WaterBudget budget = world.subsurface().budget();
        assertTrue(Math.abs(budget.imbalance()) <= 1e-6 * Math.max(1, Math.abs(budget.storage())) + 1,
                "the water budget closes: " + budget.imbalance());
        double seaHead = wm.surfaceZ(50, 0) - world.subsurface().waterTableDepthM(50, 0);
        assertEquals(0, seaHead, 0.5, "the sea fixes the water table");
        report.stage("empty sea", t, "%d×%d columns of %.0f m, sea floor %.1f m at the centre; water budget imbalance %.2g m³",
                s.initialTerrain().size(), s.initialTerrain().size(), l, floor, budget.imbalance());

        // ── the user places a chamber ──
        t = System.nanoTime();
        VolcanoDefinition def = ChamberPlacement.definition("surtur", chamber(), floor, l);
        world.addVolcano(def);
        VolcanoSystem v = s.volcano();
        assertTrue(v.vents().get(0).emergent(), "no vent yet");
        assertEquals(floor, wm.surfaceZ(0, 0), 1e-9, "nothing built: the sea floor is untouched");
        long placedAt = s.engine().currentStep();
        report.stage("placed", t, "chamber 3 km under the sea floor, %.2g m³, supply %.0f m³/s, %s", v.chamber().config().volume(),
                v.chamber().supplyRate(), "vent emergent (none yet)");

        // ── what the eruptions do ──
        t = System.nanoTime();
        Stages st = new Stages();
        double volcSeconds = 0;
        boolean dialled = false;
        double steepest = 0; // worst loose-deposit excess over the angle of repose (m), checked as the island grows
        long steepestAt = -1;
        String steepestWhere = "none";
        for (int i = 0; i < BUDGET && st.oceanEntryAfterEffusive < 0; i++) {
            v = s.volcano();
            volcSeconds = s.engine().time();
            EngineFrame f = s.engine().step();
            long step = s.engine().currentStep();
            for (EngineEvent e : f.events()) {
                if (e instanceof MagmaEvents.EruptionStarted) st.eruptions++;
                if (e instanceof LavaEvents.LavaOceanEntry) {
                    st.oceanEntries++;
                    if (st.effusive >= 0 && st.oceanEntryAfterEffusive < 0) st.oceanEntryAfterEffusive = step;
                }
            }
            if (i % 200 == 0 || st.oceanEntryAfterEffusive >= 0) {
                Edifice ed = Edifice.measure(s, floor);
                st.observe(step, volcSeconds, v, ed, s.lava().activeCellCount());
                // the world model is rebuilt when a volcano is added: read the current one
                ReposeRelaxation repose = s.terrain().world().reposeRelaxation();
                assertNotNull(repose, "a volcano's world relaxes loose deposits to their angle of repose");
                double excess = repose.worstExcessM(-60, -60, 60, 60);
                if (excess > steepest) {
                    steepest = excess;
                    steepestAt = step;
                    steepestWhere = repose.worstExcessWhere(-60, -60, 60, 60);
                }
            }
            if (!dialled && st.submarine >= 0) {
                // the live supply dial, mid-eruption: applied in place, nothing rebuilt
                double before = v.chamber().supplyRate();
                world.reconfigureLive(world.definition(), List.of(withSupply(world.volcanoDefinitions().get(0), 1.5 * before)));
                s.engine().step();
                assertEquals(1.5 * before, s.volcano().chamber().supplyRate(), 1e-9, "the dial applies at once");
                ConfigImpact.Impact impact = ConfigImpact.volcano("magma.chamber.initialTemperatureC");
                assertEquals(ConfigImpact.Kind.REINIT, impact.kind(), "a dry run classifies a restart change");
                report.line("live dial at %s: supply %.0f → %.0f m³/s (in place); dry run of initialTemperatureC: %s (%s)", st.when(step),
                        before, 1.5 * before, impact.kind(), impact.reason());
                dialled = true;
            }
        }
        report.stage("eruptions", t, "%d eruptions, %d ocean entries, %.0f h", st.eruptions, st.oceanEntries, volcSeconds / 3600);
        report.line("loose deposits: worst excess over the angle of repose (+%.2f in tan) %.4f m (step %d); %d repose moves",
                ReposeRelaxation.SLOPE_TOLERANCE_TAN, steepest, steepestAt,
                s.terrain().world().reposeRelaxation().moves());
        report.line("steepest loose column: %s", steepestWhere);
        report.line("repose drains cut short by the safety bound: %d; columns still queued: %d",
                s.terrain().world().reposeRelaxation().boundHits(), s.terrain().world().reposeRelaxation().pending());
        assertTrue(steepest <= 1e-3,
                "no loose deposit ever stands above its angle of repose: " + steepest + " m at step " + steepestAt);
        for (String d : st.history) report.line("%s", d);
        Edifice end = Edifice.measure(s, floor);
        report.line("at the end: %s (%.4f km² of land)", end, end.landColumns * l * l / 1e6);

        // ── save/restore mid-eruption and thread count (checked whatever the stages did) ──
        assertTrue(st.submarine >= 0, "no eruption to save in the middle of");
        t = System.nanoTime();
        long mid = st.submarine + 2_000;
        int more = 1_200;
        Path b = worlds.resolve("b");
        Path c = worlds.resolve("c");
        WorldTemplates.writeEmpty(ocean(), "b", SEED, b);
        WorldTemplates.writeEmpty(ocean(), "c", SEED, c);
        Scenario sb = WorldScenarios.open(b, World.ChangePolicy.REJECT);
        replay(sb, placedAt, mid, floor);
        sb.saveWorld();
        Scenario resumed = WorldScenarios.open(b, World.ChangePolicy.REJECT);
        for (int i = 0; i < more; i++) resumed.engine().step();
        Scenario single = WorldScenarios.open(c, World.ChangePolicy.REJECT, 1);
        replay(single, placedAt, mid + more, floor);
        assertEquals(single.engine().stateHash(), resumed.engine().stateHash(),
                "saved and resumed mid-eruption (default threads) = uninterrupted on one thread");
        report.stage("restore", t, "saved at step %d, resumed %d steps: identical to an uninterrupted single-thread run", mid, more);

        // ── the island's birth, stage by stage ──
        st.verify(report);
        assertTrue(end.landColumns > 0, "stage 6 (the island persists) failed: " + end);
    }

    /** A fresh copy of the flow up to {@code until}: the chamber placed at the same step (no dial). */
    private static void replay(Scenario s, long placedAt, long until, double floor) {
        while (s.engine().currentStep() < until) {
            if (s.engine().currentStep() == placedAt) {
                s.session().addVolcano(ChamberPlacement.definition("surtur", chamber(), floor, s.terrain().world().spec().metersPerColumn()));
            }
            s.engine().step();
        }
    }

    @SuppressWarnings("unchecked")
    private static VolcanoDefinition withSupply(VolcanoDefinition v, double supply) {
        Map<String, Object> tree = copy(v.toTree());
        ((Map<String, Object>) ((Map<String, Object>) tree.get("magma")).get("chamber")).put("supplyRate", supply);
        return VolcanoDefinition.parse(v.id(), ConfigNode.root("volcanoes/" + v.id() + ".yaml", tree));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object x = e.getValue();
            out.put(e.getKey(), x instanceof Map<?, ?> sub ? copy((Map<String, Object>) sub) : x instanceof List<?> l ? new ArrayList<>(l) : x);
        }
        return out;
    }

    // ── Measurements ──

    /** The edifice around the vent: its top, how much stands above the sea, the deposits. */
    record Edifice(double top, int landColumns, double volumeM3) {
        static Edifice measure(Scenario s, double floor) {
            WorldModel wm = s.terrain().world();
            double l = wm.spec().metersPerColumn();
            double top = Double.NEGATIVE_INFINITY;
            int land = 0;
            double volume = 0;
            for (int z = -R; z <= R; z++) {
                for (int x = -R; x <= R; x++) {
                    if (!wm.isKnown(x, z)) continue;
                    double zs = wm.surfaceZ(x, z);
                    top = Math.max(top, zs);
                    if (zs >= 0) land++;
                    volume += Math.max(0, zs - floor) * l * l;
                }
            }
            return new Edifice(top, land, volume);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "top %.1f m, %d land columns, %.3g m³ built", top, landColumns, volumeM3);
        }
    }

    /** When each stage of the island's birth was first seen (−1: not yet), and a log to explain them. */
    static final class Stages {
        long submarine = -1;
        long surtseyan = -1;
        long emerged = -1;
        long effusive = -1;
        long oceanEntryAfterEffusive = -1;
        int eruptions;
        int oceanEntries;
        double emergedTop;
        int emergedLand;
        final List<String> history = new ArrayList<>();
        private long lastLog = -1_000_000;
        private double hours;

        String when(long step) {
            return String.format(Locale.ROOT, "step %d (%.1f h)", step, hours);
        }

        void observe(long step, double volcSeconds, VolcanoSystem v, Edifice ed, int lavaCells) {
            hours = volcSeconds / 3600;
            boolean erupting = v.chamber().erupting();
            double water = v.coupler().waterDepthM();
            boolean phreato = v.coupler().phreatomagmatic();
            var style = v.classifier().style();
            if (submarine < 0 && erupting && water > 0 && ed.volumeM3() > 0) submarine = step;
            if (surtseyan < 0 && submarine >= 0 && (phreato || (style != null && style.name().equals("SURTSEYAN")))) surtseyan = step;
            if (emerged < 0 && ed.landColumns() > 0) {
                emerged = step;
                emergedTop = ed.top();
                emergedLand = ed.landColumns();
            }
            if (effusive < 0 && emerged >= 0 && erupting && !phreato && water == 0 && lavaCells > 0) effusive = step;
            if (step - lastLog >= 5_000 || (emerged == step) || (effusive == step)) {
                lastLog = step;
                String line = String.format(Locale.ROOT,
                        "  %s: %s, vent water %.0f m (rim open %.2f, wet fill %.2f, seepage %s), wet share %.2f, regime %s%s, style %s, %s, lava cells %d",
                        when(step), erupting ? "erupting" : "quiet", water, v.coupler().ventWater().openFraction(),
                        v.coupler().ventWater().slurryFraction(),
                        Double.isNaN(v.coupler().ventWater().seepageKgPerS()) ? "open" : String.format(Locale.ROOT, "%.0f kg/s", v.coupler().ventWater().seepageKgPerS()),
                        v.coupler().wetShare(), v.chamber().eruptiveRegime(), phreato ? " (magma–water)" : "", style, ed, lavaCells);
                history.add(line);
                System.out.println("FLOW" + line); // live progress (the report prints at the end)
                System.out.flush();
            }
        }

        /** Reports every stage, then fails naming the first that did not happen (with the edifice history). */
        void verify(Report report) {
            String[] names = {"1 submarine eruption building an edifice", "2 Surtseyan / phreatomagmatic activity (classified)",
                    "3 emergence: first land above sea level", "4 effusive lava from a dry or sealed vent",
                    "5 lava reaching the new coast (ocean entry)"};
            long[] at = {submarine, surtseyan, emerged, effusive, oceanEntryAfterEffusive};
            String missing = null;
            for (int k = 0; k < names.length; k++) {
                report.line("%s: %s%s", names[k], at[k] < 0 ? "NOT OBSERVED" : "step " + at[k],
                        k == 2 && at[k] >= 0 ? String.format(Locale.ROOT, " (top %.1f m, %d land columns)", emergedTop, emergedLand) : "");
                if (at[k] < 0 && missing == null) missing = names[k];
            }
            String diag = "\nedifice over time:\n" + String.join("\n", history);
            if (missing != null) fail("stage " + missing + " did not happen within " + BUDGET + " steps" + diag);
            for (int k = 1; k < at.length; k++) assertTrue(at[k - 1] <= at[k], "stages in order" + diag);
        }
    }

    /** Timed stages and notes, printed at the end (also when a stage fails). */
    static final class Report {
        private final List<String> lines = new ArrayList<>();
        private final long start = System.nanoTime();

        void stage(String name, long since, String fmt, Object... args) {
            lines.add(String.format(Locale.ROOT, "  %-10s %6.1f s  ", name, (System.nanoTime() - since) / 1e9)
                    + String.format(Locale.ROOT, fmt, args));
        }

        void line(String fmt, Object... args) {
            lines.add("    " + String.format(Locale.ROOT, fmt, args));
        }

        void print() {
            System.out.println("ISLAND FLOW (" + String.format(Locale.ROOT, "%.0f s", (System.nanoTime() - start) / 1e9) + ")");
            for (String l : lines) System.out.println(l);
        }
    }
}
