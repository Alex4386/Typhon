package me.alex4386.typhon.engine.assembly;

import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.CRATER;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.cone;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.lava.LavaConfig;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.SectionRaster;
import me.alex4386.typhon.engine.world.UnitRecord;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

/** Every deposit becomes a layer of the world model attributed to its volcano and eruption. */
class StratigraphyTest {
    /**
     * Physical cooling: the lava field runs on the volcano's clock (eruptive ×20, then dormant ×5000
     * under the default scaling), so after a stop flows freeze within engine minutes on their own.
     */
    private static final LavaConfig FAST_COOLING = LavaConfig.defaults();

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
        MagmaChamberConfig chamber = MagmaChamberConfig.builder(id, new BlockPos(vent.position().x(), 40, vent.position().z()))
                .initialOverpressureMPa(14.95)
                .supplyVariability(0)
                .build();
        return VolcanoSystem.builder(id, List.of(vent), terrain, lava)
                .chamber(chamber)
                .scaling(VolcanoScaling.DEFAULT)
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
        Engine.Builder builder = Engine.builder(21).add(terrain);
        volcano.addTo(builder).add(lava);
        Engine e = builder.build();
        e.submit(cone());
        WorldModel world = terrain.world();

        run(e, 20 * 60 * 2); // eruption 1 pours lava
        assertEquals(1, volcano.chamber().eruptionCount());
        e.submit(new MagmaCommands.StopEruption("test"));
        run(e, 20 * 60 * 4); // the thinner parts of the flow freeze (the crater pond stays molten)
        volcano.tephra().startPhase(ExplosivePhase.strombolian(CRATER, 5e5)); // ash on the cooled flow
        run(e, 20 * 60 * 2);
        volcano.tephra().stopPhase();
        run(e, 20 * 30);
        e.submit(new MagmaCommands.StartEruption("test"));
        run(e, 20 * 60 * 2); // eruption 2 pours lava over it
        assertEquals(2, volcano.chamber().eruptionCount());
        e.submit(new MagmaCommands.StopEruption("test"));
        run(e, 20 * 60 * 4);

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
        SectionRaster section = world.section(new double[] {x + 0.5, z - 0.5, x + 0.5, z + 1.5},
                surface - 400, surface + 1, 1, 4010); // flows emplaced at ×20 are tens of metres thick
        List<String> rows = new ArrayList<>();
        for (int iz = 0; iz < section.nz(); iz++) {
            int unit = section.unit()[section.index(0, iz)];
            if (unit < 0 || world.unit(unit).volcanoId() == null) continue;
            UnitRecord u = world.unit(unit);
            String label = u.type() + "#" + u.eruptionId();
            if (rows.isEmpty() || !rows.get(rows.size() - 1).equals(label)) rows.add(label);
        }
        assertEquals("LAVA#2", rows.get(0), "section top is the youngest lava: " + rows);
        assertTrue(rows.indexOf("FALL#1") < rows.lastIndexOf("LAVA#1"), "fall above the older lava: " + rows);
    }

    @Test
    void depositsAreAttributedToTheirOwnVolcano() {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain, FAST_COOLING);
        VentSite eastVent = VentSite.crater("summit", new BlockPos(40, 101, 0), 3);
        VentSite westVent = VentSite.crater("summit", new BlockPos(-40, 101, 0), 3);
        VolcanoSystem east = system("east", eastVent, terrain, lava);
        VolcanoSystem west = system("west", westVent, terrain, lava);
        Engine.Builder builder = Engine.builder(5).add(terrain);
        east.addTo(builder);
        west.addTo(builder);
        builder.add(lava);
        Engine e = builder.build();
        e.submit(cone());
        run(e, 20 * 60 * 2);
        assertTrue(east.chamber().eruptionCount() > 0 && west.chamber().eruptionCount() > 0, "both erupt");
        e.submit(new MagmaCommands.StopEruption("east"));
        e.submit(new MagmaCommands.StopEruption("west"));
        run(e, 20 * 60 * 2);

        WorldModel world = terrain.world();
        List<UnitRecord> eastColumn = volcanicUnits(world, 40, 0);
        List<UnitRecord> westColumn = volcanicUnits(world, -40, 0);
        assertTrue(eastColumn.stream().anyMatch(u -> u.type() == DepositType.LAVA), "east lava: " + labels(eastColumn));
        assertTrue(westColumn.stream().anyMatch(u -> u.type() == DepositType.LAVA), "west lava: " + labels(westColumn));
        for (UnitRecord u : eastColumn) assertEquals("east", u.volcanoId());
        for (UnitRecord u : westColumn) assertEquals("west", u.volcanoId());
    }
}
