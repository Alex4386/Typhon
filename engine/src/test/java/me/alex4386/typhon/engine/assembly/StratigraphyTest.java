package me.alex4386.typhon.engine.assembly;

import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.CRATER;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.basalt;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.cone;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.run;
import static me.alex4386.typhon.engine.assembly.VolcanoSystemTest.world;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

    @Test
    void twoEruptionsStackLavaOverFallOverOlderLava() {
        VolcanoSystemTest.World w = world(21, basalt(), null);
        Engine e = w.engine();
        WorldModel world = w.terrain().world();

        run(e, 20 * 60 * 3); // eruption 1 pours lava
        assertEquals(1, w.volcano().chamber().eruptionCount());
        e.submit(new MagmaCommands.StopEruption("test"));
        run(e, 20 * 60 * 4); // the flow freezes
        w.volcano().tephra().startPhase(ExplosivePhase.strombolian(CRATER, 5e5)); // ash on the cooled flow
        run(e, 20 * 60 * 2);
        w.volcano().tephra().stopPhase();
        run(e, 20 * 60);
        e.submit(new MagmaCommands.StartEruption("test"));
        run(e, 20 * 60 * 3); // eruption 2 pours lava over it
        assertEquals(2, w.volcano().chamber().eruptionCount());
        e.submit(new MagmaCommands.StopEruption("test"));
        run(e, 20 * 60 * 4);

        // A column on the crater floor took both flows and the ash between them.
        int x = CRATER.position().x() + 1;
        int z = CRATER.position().z();
        List<UnitRecord> units = volcanicUnits(world, x, z);
        List<String> labels = new ArrayList<>();
        for (UnitRecord u : units) labels.add(u.type() + "#" + u.eruptionId());
        int lava1 = labels.indexOf("LAVA#1");
        int fall = labels.indexOf("FALL#1");
        int lava2 = labels.lastIndexOf("LAVA#2");
        assertTrue(lava1 >= 0 && fall > lava1 && lava2 > fall, "bottom to top: LAVA#1 < FALL#1 < LAVA#2, got " + labels);
        for (UnitRecord u : units) assertEquals("test", u.volcanoId());

        // The section through the vent shows the same order.
        double surface = world.surfaceZ(x, z);
        SectionRaster section = world.section(new double[] {x - 0.5, z + 0.5, x + 1.5, z + 0.5},
                surface - 40, surface + 1, 2, 400);
        // rows run top (iz = 0) to bottom: the first volcanic unit met is the youngest lava
        List<String> rows = new ArrayList<>();
        for (int iz = 0; iz < section.nz(); iz++) {
            int unit = section.unit()[iz * section.nu()];
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
        LavaFlow lava = new LavaFlow(terrain);
        VentSite eastVent = VentSite.crater("summit", new BlockPos(40, 99, 0), 3);
        VentSite westVent = VentSite.crater("summit", new BlockPos(-40, 99, 0), 3);
        VolcanoSystem east = system("east", eastVent, terrain, lava);
        VolcanoSystem west = system("west", westVent, terrain, lava);
        Engine.Builder builder = Engine.builder(5).add(terrain);
        east.addTo(builder);
        west.addTo(builder);
        builder.add(lava);
        Engine e = builder.build();
        e.submit(cone());
        run(e, 20 * 60 * 3);
        assertTrue(east.chamber().eruptionCount() > 0 && west.chamber().eruptionCount() > 0);

        WorldModel world = terrain.world();
        for (UnitRecord u : volcanicUnits(world, 40, 0)) assertEquals("east", u.volcanoId(), "east vent column");
        for (UnitRecord u : volcanicUnits(world, -40, 0)) assertEquals("west", u.volcanoId(), "west vent column");
        assertTrue(volcanicUnits(world, 40, 0).stream().anyMatch(u -> u.type() == DepositType.LAVA));
        assertTrue(volcanicUnits(world, -40, 0).stream().anyMatch(u -> u.type() == DepositType.LAVA));
    }

    private static VolcanoSystem system(String id, VentSite vent, TerrainModel terrain, LavaFlow lava) {
        MagmaChamberConfig chamber = MagmaChamberConfig.builder(id, new BlockPos(vent.position().x(), 40, 0))
                .initialOverpressureMPa(14.95)
                .supplyVariability(0)
                .build();
        return VolcanoSystem.builder(id, List.of(vent), terrain, lava)
                .chamber(chamber)
                .scaling(VolcanoScaling.DEFAULT)
                .dikesEnabled(false)
                .build();
    }
}
