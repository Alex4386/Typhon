package me.alex4386.typhon.engine.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import org.junit.jupiter.api.Test;

/**
 * A save cut off midway (crash, kill, a copy taken while saving) must still restore: the world's unit table
 * (in its subsystem JSON) is written before the stacks that reference it, and unknown unit ids read as
 * unattributed instead of failing ("Unknown unit id 463" in a live world's lahars).
 */
class InterruptedSaveTest {
    private static final double L = 10;

    private static TerrainModel terrain() {
        WorldSpec spec = new WorldSpec(L, 8 * L, -2000, 0, List.of(new WorldSpec.GeologyLayer("granite", -500, 0.01)),
                "basalt", "basalt", L);
        TerrainModel terrain = new TerrainModel(new WorldModel(spec));
        for (int x = -40; x < 40; x++) for (int z = -40; z < 40; z++) terrain.world().importColumn(x, z, -50, MaterialTable.BASALT);
        return terrain;
    }

    /** A store that fails after {@code budget} writes, on top of a copy of {@code base}; counts its writes. */
    private static final class CrashingStore implements SaveStore {
        private final InMemorySaveStore inner = new InMemorySaveStore();
        private long budget;
        private int writes;

        CrashingStore(InMemorySaveStore base, long budget) {
            for (Map.Entry<String, byte[]> e : base.files().entrySet()) inner.write(e.getKey(), e.getValue());
            this.budget = budget;
        }

        @Override
        public byte[] read(String path) {
            return inner.read(path);
        }

        @Override
        public boolean write(String path, byte[] data) {
            if (budget-- <= 0) throw new IllegalStateException("crash");
            writes++;
            return inner.write(path, data);
        }

        @Override
        public void append(String path, byte[] data) {
            inner.append(path, data);
        }

        @Override
        public List<String> list(String prefix) {
            return inner.list(prefix);
        }

        @Override
        public void delete(String path) {
            inner.delete(path);
        }
    }

    @Test
    void aSaveCutOffAtAnyWriteRestoresWithEveryUnitKnown() {
        TerrainModel terrain = terrain();
        Engine engine = Engine.builder(1).add(terrain).build();
        InMemorySaveStore before = new InMemorySaveStore();
        engine.save(before);

        // new eruptions add units and lay deposits referencing them across several regions
        WorldModel w = terrain.world();
        for (int i = 0; i < 6; i++) {
            int unit = w.newUnit(UnitRecord.of(DepositType.FALL));
            for (int x = -40; x < 40; x += 7) for (int z = -40; z < 40; z += 5) w.deposit(x, z, 0.3, MaterialTable.ASH, unit);
        }
        CrashingStore full = new CrashingStore(before, Long.MAX_VALUE);
        engine.save(full);
        int writes = full.writes;
        assertTrue(writes > 1, "the save writes the JSON and region files: " + writes);

        for (int n = 0; n <= writes; n++) {
            CrashingStore torn = new CrashingStore(before, n);
            try {
                engine.save(torn);
            } catch (IllegalStateException crash) {
                // cut off after n writes
            }
            TerrainModel restored = terrain();
            Engine.builder(1).add(restored).restore(torn).build();
            WorldModel r = restored.world();
            for (int x = -40; x < 40; x++) {
                for (int z = -40; z < 40; z++) {
                    for (int k = 0; k < r.layerCount(x, z); k++) {
                        int unit = r.layer(x, z, k).unit();
                        assertTrue(unit < r.units().size(), "cut after " + n + " writes: layer unit " + unit
                                + " beyond the table (" + r.units().size() + ")");
                    }
                }
            }
        }
    }

    @Test
    void anUnknownUnitIdReadsAsUnattributed() {
        WorldModel w = terrain().world();
        assertEquals(w.unit(UnitTable.UNATTRIBUTED), w.unit(9_999), "a torn save degrades provenance, not the load");
    }
}
