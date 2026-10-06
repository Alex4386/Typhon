package me.alex4386.typhon.engine.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.Saves;
import org.junit.jupiter.api.Test;

/** Worlds saved before the repose rule relax their loose deposits once on load; newer saves are untouched. */
class ReposeMigrationTest {
    private static final double L = 10;
    private static final int R = 20;

    private static TerrainModel terrain() {
        WorldSpec spec = new WorldSpec(L, 8 * L, -2000, 0, List.of(new WorldSpec.GeologyLayer("granite", -500, 0.01)),
                "basalt", "basalt", L);
        TerrainModel terrain = new TerrainModel(new WorldModel(spec));
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) terrain.world().importColumn(x, z, 0, MaterialTable.BASALT);
        return terrain;
    }

    private static double worst(WorldModel w) {
        return w.reposeRelaxation().worstExcessM(-R + 1, -R + 1, R - 1, R - 1);
    }

    @Test
    void aPreFixSpikeRelaxesOnceOnLoad() {
        TerrainModel before = terrain();
        // the old behaviour: a 100 m tephra tower on one column, saved without the repose rule
        before.world().deposit(0, 0, 100, MaterialTable.ASH, 1, LayerFlags.LOOSE, 0.45, 0);
        before.world().markPreRepose();
        Engine old = Engine.builder(1).add(before).build();
        InMemorySaveStore saved = Saves.save(old);

        TerrainModel after = terrain();
        after.world().enableReposeRelaxation(); // volcano assembly enables it before the state is restored
        Engine restored = Engine.builder(1).add(after).restore(saved).build();
        WorldModel w = after.world();
        assertTrue(worst(w) <= 1e-4, "the spike relaxed to the angle of repose on load: " + worst(w));
        assertTrue(w.surfaceZ(0, 0) < 30, "no tower left: " + w.surfaceZ(0, 0));
        assertEquals(WorldModel.REPOSE_VERSION, w.reposeVersion(), "the sweep is recorded");
        double volume = 0;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) volume += w.surfaceZ(x, z) * L * L;
        assertEquals(100 * L * L, volume, 1e-3 * 100 * L * L, "volume conserved");

        // saved again and restored, nothing moves any more
        InMemorySaveStore again = Saves.save(restored);
        TerrainModel third = terrain();
        third.world().enableReposeRelaxation();
        Engine reloaded = Engine.builder(1).add(third).restore(again).build();
        assertEquals(restored.stateHash(), reloaded.stateHash(), "the migration runs once");
        assertEquals(0, third.world().reposeRelaxation().moves(), "no moves on the second load");
    }

    @Test
    void freshWorldsRestoreBitForBit() {
        TerrainModel first = terrain();
        first.world().enableReposeRelaxation();
        first.world().deposit(3, 3, 0.5, MaterialTable.ASH, 1, LayerFlags.LOOSE, 0.45, 0);
        Engine engine = Engine.builder(1).add(first).build();
        InMemorySaveStore saved = Saves.save(engine);
        TerrainModel second = terrain();
        second.world().enableReposeRelaxation();
        Engine restored = Engine.builder(1).add(second).restore(saved).build();
        assertEquals(engine.stateHash(), restored.stateHash());
        assertEquals(0, second.world().reposeRelaxation().moves(), "a current world is not swept");
    }
}
