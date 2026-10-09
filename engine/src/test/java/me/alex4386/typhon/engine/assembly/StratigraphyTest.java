package me.alex4386.typhon.engine.assembly;

import me.alex4386.typhon.engine.testing.TestConduits;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.CRATER;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.cone;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.run;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.runPastOnset;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.lava.LavaConfig;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.volcano.VentCommands;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.SectionRaster;
import me.alex4386.typhon.engine.world.UnitRecord;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

/** Every deposit becomes a layer of the world model attributed to its volcano and eruption. */
class StratigraphyTest {
    /** Physical cooling: after a stop, thin flows freeze within days on their own. */
    private static final LavaConfig FAST_COOLING = LavaConfig.defaults();
    private static final double DAY = 86_400;
    /** Length of each eruption (s). */
    private static final double ERUPTION = 18 * 60;
    /** Quiet time after a stop for the thinner parts of a flow to freeze (s). */
    private static final double FREEZE = 14 * DAY;

    /** Units of the volcanic layers of a column, bottom to top (consecutive duplicates collapsed). */
    static List<UnitRecord> volcanicUnits(WorldModel world, int x, int z) {
        List<UnitRecord> out = new ArrayList<>();
        int previous = -1;
        for (int k = 0; k < world.layerCount(x, z); k++) {
            LayerView layer = world.layer(x, z, k);
            UnitRecord u = world.unit(layer.unit());
            if (u.volcanoId() == null || layer.unit() == previous) continue;
            out.add(u);
            previous = layer.unit();
        }
        return out;
    }

    /** Total thickness (m) of the column's layers with this {@code TYPE#eruption} label. */
    static double thickness(WorldModel world, int x, int z, String label) {
        double total = 0;
        double bottom = Double.NaN;
        for (int k = 0; k < world.layerCount(x, z); k++) {
            LayerView layer = world.layer(x, z, k);
            UnitRecord u = world.unit(layer.unit());
            if (k > 0 && (u.type() + "#" + u.eruptionId()).equals(label)) total += layer.top() - bottom;
            bottom = layer.top();
        }
        return total;
    }

    static List<String> labels(List<UnitRecord> units) {
        List<String> out = new ArrayList<>();
        for (UnitRecord u : units) out.add(u.type() + "#" + u.eruptionId());
        return out;
    }

    private static VolcanoSystem system(String id, VentSite vent, TerrainModel terrain, LavaFlow lava) {
        MagmaChamberConfig chamber = MagmaChamberConfig.builder(id, vent.position().offset(0, -3000, 0))
                .conduit(TestConduits.molten())
                .initialOverpressureMPa(15.05)
                .initialWaterWt(0.3).rechargeWaterWt(0.3) // gas-poor: lava rather than fountain tephra
                .supplyVariability(0)
                .build();
        return VolcanoSystem.builder(id, List.of(vent), terrain, lava)
                .chamber(chamber)
                .dikesEnabled(false)
                .massFlowsEnabled(false)
                .geothermalEnabled(false)
                .build();
    }

    @Test
    void twoEruptionsStackLavaOverFallOverOlderLava() {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain, FAST_COOLING);
        VolcanoSystem volcano = system("test", CRATER, terrain, lava);
        Engine.Builder builder = Engine.builder(21).adaptive(Engine.DEFAULT_MAX_STEP_SECONDS).add(terrain);
        volcano.addTo(builder).add(lava);
        Engine e = builder.build();
        e.submit(cone());
        WorldModel world = terrain.world();

        runPastOnset(e, 30 * DAY, ERUPTION); // eruption 1 pours lava
        assertEquals(1, volcano.chamber().eruptionCount());
        // plugging the crater ends it, the conduit below still molten (stopping by hand would plug it solid)
        e.submit(new VentCommands.SealVent("test", CRATER.id()));
        run(e, FREEZE); // the thinner parts of the flow freeze (the crater pond stays molten)
        volcano.tephra().startPhase(ExplosivePhase.strombolian(CRATER, 5e5)); // bombs and ash on the cooled flow
        run(e, 1800);
        volcano.tephra().stopPhase();
        run(e, 3600);
        e.submit(new VentCommands.UnsealVent("test", CRATER.id()));
        e.submit(new MagmaCommands.StartEruption("test"));
        run(e, ERUPTION); // eruption 2 pours lava over it
        assertEquals(2, volcano.chamber().eruptionCount());
        e.submit(new MagmaCommands.StopEruption("test"));
        run(e, FREEZE);

        // Wherever both flows have frozen, the older lies below the younger; somewhere the ash that
        // fell on the cooled first flow is sandwiched between them.
        int both = 0;
        int x = Integer.MIN_VALUE;
        int z = 0;
        double best = 0;
        for (int cx = -50; cx <= 50; cx++) {
            for (int cz = -50; cz <= 50; cz++) {
                List<UnitRecord> units = volcanicUnits(world, cx, cz);
                for (UnitRecord u : units) assertEquals("test", u.volcanoId());
                List<String> column = labels(units);
                int lava1 = column.indexOf("LAVA#1");
                int lava2 = column.lastIndexOf("LAVA#2");
                if (lava1 < 0 || lava2 < 0) continue;
                both++;
                // The first flow reaches every column first. (Where the second eruption fed into the
                // still-molten first pond, residual first-eruption melt can freeze in between later
                // layers, so the two may interleave above that.)
                assertTrue(lava1 < column.indexOf("LAVA#2"), "older lava first at " + cx + "," + cz + ": " + column);
                int fall = column.subList(lava1, lava2).indexOf("FALL#1");
                // the section check below needs layers it can resolve: take the column whose
                // thinnest of the three layers is thickest
                double thinnest = Math.min(thickness(world, cx, cz, "LAVA#1"),
                        Math.min(thickness(world, cx, cz, "FALL#1"), thickness(world, cx, cz, "LAVA#2")));
                if (fall > 0 && thinnest > best) {
                    best = thinnest;
                    x = cx;
                    z = cz;
                }
            }
        }
        assertTrue(both > 0, "the second flow covers part of the first");
        assertTrue(x != Integer.MIN_VALUE, "a column with LAVA#1 < FALL#1 < LAVA#2");

        // The section through the column shows the same order, read from the top down.
        double surface = world.surfaceZ(x, z);
        double l = world.spec().metersPerColumn();
        // through the column's centre, 1 cm rows from below the older flow to the top (the fall may be cm or,
        // next to the crater, tens of metres thick)
        double base = surface;
        for (int k = 0; k < world.layerCount(x, z); k++) {
            LayerView layer = world.layer(x, z, k);
            UnitRecord u = world.unit(layer.unit());
            if (u.volcanoId() != null && (u.type() + "#" + u.eruptionId()).equals("LAVA#1")) base = Math.min(base, layer.bottom());
        }
        double bottom = base - 1;
        SectionRaster section = world.section(new double[] {(x + 0.5) * l, (z + 0.5) * l, (x + 0.5) * l, (z + 1) * l},
                bottom, surface + 1, 1, (int) Math.ceil((surface + 1 - bottom) / 0.01));
        List<String> rows = new ArrayList<>();
        for (int iz = 0; iz < section.nz(); iz++) {
            int unit = section.unit()[section.index(0, iz)];
            if (unit < 0 || world.unit(unit).volcanoId() == null) continue;
            UnitRecord u = world.unit(unit);
            String label = u.type() + "#" + u.eruptionId();
            if (rows.isEmpty() || !rows.get(rows.size() - 1).equals(label)) rows.add(label);
        }
        // Eruption 2's own Strombolian bursts may leave a fall veneer on its lava (FALL#2 over LAVA#2).
        assertTrue(rows.get(0).endsWith("#2"), "section top belongs to the youngest eruption: " + rows);
        assertEquals("LAVA#2", rows.stream().filter(r -> r.startsWith("LAVA")).findFirst().orElse(null),
                "the uppermost lava is the youngest: " + rows);
        StringBuilder layers = new StringBuilder();
        for (int k = 0; k < world.layerCount(x, z); k++) {
            LayerView layer = world.layer(x, z, k);
            UnitRecord u = world.unit(layer.unit());
            if (u.volcanoId() != null) layers.append(String.format(" %s#%d %.3f..%.3f", u.type(), u.eruptionId(), layer.bottom(), layer.top()));
        }
        assertTrue(rows.indexOf("FALL#1") < rows.lastIndexOf("LAVA#1"),
                "fall above the older lava: " + rows + " at " + x + "," + z + ":" + layers);
    }

    @Test
    void depositsAreAttributedToTheirOwnVolcano() {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain, FAST_COOLING);
        // two flank craters 400 m east and west of the summit (columns ±40 at 10 m)
        VentSite eastVent = VentSite.crater("summit", new Point3(405, 420, 5), 20);
        VentSite westVent = VentSite.crater("summit", new Point3(-395, 420, 5), 20);
        VolcanoSystem east = system("east", eastVent, terrain, lava);
        VolcanoSystem west = system("west", westVent, terrain, lava);
        Engine.Builder builder = Engine.builder(5).adaptive(Engine.DEFAULT_MAX_STEP_SECONDS).add(terrain);
        east.addTo(builder);
        west.addTo(builder);
        builder.add(lava);
        Engine e = builder.build();
        e.submit(cone());
        for (double end = e.time() + 30 * DAY; e.time() < end
                && (east.chamber().eruptionCount() == 0 || west.chamber().eruptionCount() == 0); ) {
            e.step();
        }
        run(e, ERUPTION);
        assertTrue(east.chamber().eruptionCount() > 0 && west.chamber().eruptionCount() > 0, "both erupt");
        e.submit(new MagmaCommands.StopEruption("east"));
        e.submit(new MagmaCommands.StopEruption("west"));
        run(e, FREEZE);

        WorldModel world = terrain.world();
        List<UnitRecord> eastColumn = volcanicUnits(world, 40, 0);
        List<UnitRecord> westColumn = volcanicUnits(world, -40, 0);
        assertTrue(eastColumn.stream().anyMatch(u -> u.type() == DepositType.LAVA), "east lava: " + labels(eastColumn));
        assertTrue(westColumn.stream().anyMatch(u -> u.type() == DepositType.LAVA), "west lava: " + labels(westColumn));
        for (UnitRecord u : eastColumn) assertEquals("east", u.volcanoId());
        for (UnitRecord u : westColumn) assertEquals("west", u.volcanoId());
    }
}
