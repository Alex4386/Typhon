package me.alex4386.typhon.engine.dike;

import static me.alex4386.typhon.engine.dike.DikeTestWorld.basalt;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.fastConfig;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.flat;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.run;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.world;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.Provenance;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

/** A dike that freezes on its way up leaves intrusive rock at depth in the world model. */
class DikeIntrusionTest {
    @Test
    void stalledDikeLeavesIntrusionAtDepth() {
        // 2 MPa: rises part-way from the chamber roof and freezes (1 MPa freezes before it leaves the roof)
        DikeTestWorld.World w = world(4, basalt(2).build(), fastConfig(), flat(), null);
        WorldModel world = w.terrain().world();
        int unit = Provenance.unitFor(world, "v", 0, DepositType.INTRUSION, 0, Double.NaN, 50);
        w.dikes().setUnits((type, time, temperature) -> type == DepositType.INTRUSION ? unit : 0);
        w.engine().submit(new DikeCommands.ForceDike("v"));
        run(w.engine(), 20 * 60 * 30);

        Dike dike = w.dikes().dikes().get(0);
        assertEquals(DikeStatus.STALLED, dike.status());
        double l = world.spec().metersPerColumn();
        int x = (int) Math.floor(dike.x() / l);
        int z = (int) Math.floor(dike.z() / l);
        double surface = world.surfaceZ(x, z);
        double tipZ = surface - dike.depthM();
        if (tipZ <= world.spec().datumZ()) return; // stalled below the modelled crust: nothing to record

        boolean intrusion = false;
        for (int k = 0; k < world.layerCount(x, z); k++) {
            LayerView layer = world.layer(x, z, k);
            if (layer.unit() == unit) {
                intrusion = true;
                assertEquals(MaterialTable.GABBRO.id(), layer.material(), "basaltic dike crystallises as gabbro");
                assertTrue(layer.top() <= tipZ + 1e-3, "intrusion stops at the tip");
                assertTrue(layer.top() < surface, "buried, not at the surface");
            }
        }
        assertTrue(intrusion, "INTRUSION layer in the dike's column");
        assertTrue(world.isSolid(x, z, tipZ - 1), "intrusion is solid rock");
    }
}
