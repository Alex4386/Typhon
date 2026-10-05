package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import me.alex4386.typhon.engine.deformation.DeformationEvents.DeformationSample;
import me.alex4386.typhon.engine.deformation.GeodeticStation;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.worlds.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StationsTest {
    @Test
    void presetsConfigureStationsThatReportReadings() {
        for (String name : List.of("kilauea", "kilauea-real", "st-helens-real", "yellowstone-real")) {
            Scenario s = Presets.get(name).build(1);
            List<GeodeticStation> stations = s.volcano().deformation().config().stations;
            assertFalse(stations.isEmpty(), name);
            DeformationSample sample = null;
            for (int i = 0; i < 20 * 60 && sample == null; i++) {
                EngineFrame f = s.engine().step();
                for (EngineEvent e : f.events()) if (e instanceof DeformationSample d) sample = d;
            }
            assertTrue(sample != null && sample.stations().size() == stations.size(), name);
        }
        assertTrue(Presets.get("kilauea-real").build(1).volcano().deformation().config().stations.stream()
                .anyMatch(st -> st.name().equals("UWEV")));
    }

    @Test
    void stationsSurviveTheWorldTemplate(@TempDir Path dir) {
        WorldScenarios.writeFromPreset(Presets.get("kilauea-real"), 1, dir);
        Scenario s = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        assertEquals(Presets.get("kilauea-real").build(1).volcano().deformation().config().stations,
                s.volcano().deformation().config().stations);
    }
}
