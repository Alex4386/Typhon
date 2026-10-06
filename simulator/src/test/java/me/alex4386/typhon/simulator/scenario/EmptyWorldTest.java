package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import me.alex4386.typhon.engine.config.ChamberPlacement;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.worlds.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Worlds with nothing in them, and magma chambers placed into them by the user. */
class EmptyWorldTest {
    /** A small ocean: 2 km of sea floor 130 m deep. */
    static WorldTemplates.Template smallOcean() {
        WorldTemplates.Template d = WorldTemplates.Template.defaults("ocean");
        return new WorldTemplates.Template("ocean", 2_000, d.metersPerColumn(), d.depthM(), d.elevationM(), d.slope(),
                d.seaLevelZ(), d.roughnessM());
    }

    @Test
    void anEmptyOceanRunsSavesAndResumesWithoutVolcanoes(@TempDir Path dir) {
        WorldTemplates.writeEmpty(smallOcean(), "sea", 7, dir);
        assertTrue(Files.isDirectory(dir.resolve("volcanoes")) || !Files.exists(dir.resolve("volcanoes")));
        Scenario s = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        assertTrue(s.volcanoes().isEmpty());
        assertNull(s.volcano());
        s.engine().runFor(5);

        WorldModel wm = s.terrain().world();
        int blockTops = 0;
        int known = 0;
        for (int x = -90; x < 90; x += 7) {
            for (int z = -90; z < 90; z += 7) {
                if (!wm.isKnown(x, z)) continue;
                known++;
                double zs = wm.surfaceZ(x, z);
                assertTrue(zs < -100 && zs > -160, "sea floor ~130 m deep: " + zs);
                assertEquals(0, wm.waterZ(x, z), 1e-9, "sea level everywhere");
                double r = zs / wm.spec().metersPerColumn();
                if (Math.abs(r - Math.rint(r)) < 1e-6) blockTops++;
            }
        }
        assertTrue(known > 400);
        assertTrue(blockTops < known / 20, "a smooth sea floor, not block terraces: " + blockTops + "/" + known);

        s.saveWorld();
        Scenario resumed = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        s.engine().runFor(3);
        resumed.engine().runFor(3);
        assertEquals(s.engine().stateHash(), resumed.engine().stateHash(), "an empty world resumes exactly");
    }

    @Test
    void aPlacedChamberBecomesAVolcanoWithAnEmergentVentAndCanBeRemoved(@TempDir Path dir) {
        WorldTemplates.writeEmpty(smallOcean(), "sea", 7, dir);
        Scenario s = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        s.engine().step();
        World world = s.session();
        double ground = s.terrain().world().surfaceZ(0, 0);
        VolcanoDefinition v = ChamberPlacement.definition("first",
                new ChamberPlacement.Request(null, 0, 0, 3000, null, null, null, null, null, null, null, null, null), ground,
                s.terrain().world().spec().metersPerColumn());
        world.addVolcano(v);
        assertEquals(1, s.volcanoes().size());
        VentSite vent = s.volcano().vents().get(0);
        assertTrue(vent.emergent(), "nothing built: the vent is where the conduit will meet the ground");
        assertEquals((int) Math.ceil(ground / 10 - 1e-6) - 1, vent.position().y(), "the vent sits on the sea floor");
        double chamberTop = s.volcano().chamber().config().center().y() * 10.0;
        assertTrue(ground - chamberTop > 2900 && ground - chamberTop < 3100, "3 km below the sea floor");
        assertEquals(ChamberPlacement.defaultOf("volumeM3"), s.volcano().chamber().config().volume(), 1e-3);
        s.engine().runFor(5);

        // the definition round-trips through YAML (the server writes volcanoes/<id>.yaml)
        VolcanoDefinition again = VolcanoDefinition.parse("first",
                me.alex4386.typhon.engine.config.ConfigNode.root("first.yaml", v.toTree()));
        assertTrue(again.vents().get(0).emergent());

        s.saveWorld();
        Scenario resumed = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        assertEquals(1, resumed.volcanoes().size(), "the placed volcano is saved with the world");
        s.engine().runFor(3);
        resumed.engine().runFor(3);
        assertEquals(s.engine().stateHash(), resumed.engine().stateHash(), "and resumes exactly");

        world.removeVolcano("first");
        assertTrue(s.volcanoes().isEmpty());
        s.engine().runFor(3);
    }
}
