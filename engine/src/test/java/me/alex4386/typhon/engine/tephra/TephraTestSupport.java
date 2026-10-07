package me.alex4386.typhon.engine.tephra;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.terrain.GroundColumn;
import me.alex4386.typhon.engine.terrain.GroundImport;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.testing.TestGround.Elevation;

final class TephraTestSupport {
    private TephraTestSupport() {}

    /** Dry, soil-covered ground over 16-column chunks within ±radius chunks of the origin; surface (m) from {@code ground}. */
    static GroundImport terrain(int chunkRadius, Elevation ground) {
        List<GroundColumn> columns = new ArrayList<>();
        for (int z = -chunkRadius * 16; z < chunkRadius * 16 + 16; z++) {
            for (int x = -chunkRadius * 16; x < chunkRadius * 16 + 16; x++) {
                columns.add(GroundColumn.dry(x, z, ground.at(x, z), null));
            }
        }
        return new GroundImport(columns);
    }

    static GroundImport flat(int chunkRadius, double groundZ) {
        return terrain(chunkRadius, (x, z) -> groundZ);
    }

    /** What a host does after a restart: re-send the (engine-modified) ground. */
    static GroundImport resample(TerrainModel model, int chunkRadius) {
        return TestGround.copy(model.world(), -chunkRadius * 16, -chunkRadius * 16, chunkRadius * 16 + 15,
                chunkRadius * 16 + 15);
    }

    static <T extends EngineEvent> List<T> events(List<EngineFrame> frames, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EngineFrame frame : frames) {
            for (EngineEvent event : frame.events()) {
                if (type.isInstance(event)) out.add(type.cast(event));
            }
        }
        return out;
    }
}
