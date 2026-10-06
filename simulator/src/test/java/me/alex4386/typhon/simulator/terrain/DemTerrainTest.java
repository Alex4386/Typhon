package me.alex4386.typhon.simulator.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.RealSetting;
import me.alex4386.typhon.simulator.scenario.Scenario;
import me.alex4386.typhon.simulator.scenario.WorldScenarios;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemTerrainTest {
    @TempDir
    Path dir;

    /**
     * A 1-arc-second geographic float32 GeoTIFF (deflate, tiled) of a 1500 m cone whose summit is at
     * ({@code lat}, {@code lon}), on an 800 m plain, with a void patch.
     */
    static Path coneDem(Path dir, double lat, double lon, int size) throws IOException {
        double step = 1 / 3600.0;
        double west = lon - size / 2 * step;
        double north = lat + size / 2 * step;
        double mPerLon = 111_320 * Math.cos(Math.toRadians(lat));
        double[] data = new double[size * size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                double y = (north - (r + 0.5) * step - lat) * 110_574;
                double x = (west + (c + 0.5) * step - lon) * mPerLon;
                double d = Math.sqrt(x * x + y * y);
                data[r * size + c] = 800 + 700 * Math.max(0, 1 - d / 4000);
            }
        }
        for (int r = 10; r < 14; r++) for (int c = 10; c < 14; c++) data[r * size + c] = -32768;
        TiffWriter.Spec spec = TiffWriter.Spec.plain(ByteOrder.LITTLE_ENDIAN, 32, 3).compression(8, 3).tiled(64)
                .geo(new double[] {step, step}, new double[] {0, 0, west, north}, true, 4326, false).nodata("-32768");
        Path file = dir.resolve("cone.tif");
        Files.write(file, TiffWriter.write(size, size, data, spec));
        return file;
    }

    @Test
    void loadsAConeCentredOnTheGivenCoordinates() throws IOException {
        Path file = coneDem(dir, 19.4069, -155.2834, 500);
        ColumnGrid g = DemTerrain.load(file, 20, 128, Double.NaN, 19.4069, -155.2834);
        assertEquals(2 * 128, g.size());
        double summit = (g.ground(0, 0) + 1) * 20.0;
        assertEquals(1500, summit, 25, "summit at the domain centre");
        assertEquals(g.maxGround(), g.ground(0, 0), 1);
        double at2km = (g.ground(100, 0) + 1) * 20.0; // 2 km east
        assertEquals(800 + 700 * 0.5, at2km, 25);
        assertTrue(g.minGround() >= DemImporter.groundBlock(780, 20), "voids filled from their neighbours");
    }

    @Test
    void realPresetRunsOnADemAndItsWorldTemplateReopensIdentically() throws IOException {
        Preset preset = Presets.get("kilauea-real");
        RealSetting real = preset.realSetting();
        Path file = coneDem(dir, real.dem().lat(), real.dem().lon(), 700);
        // the world keeps DEM data as far as it may grow (expansion.maxExtentM)
        int reach = Math.max(real.halfExtentColumns(), (int) Math.round(
                me.alex4386.typhon.engine.expansion.ExpansionConfig.DEFAULTS.maxExtentM() / 2 / real.metersPerColumn()));
        ColumnGrid g = DemTerrain.load(file, real.metersPerColumn(), real.halfExtentColumns(), reach,
                real.spec().seaLevelZ(), real.dem().lat(), real.dem().lon());
        Scenario scenario = preset.build(4, g, Scenario.Options.DEFAULT);
        VentSite vent = scenario.volcano().vents().get(0);
        assertEquals(g.ground(0, 0), vent.position().y(), "vent re-anchored on the DEM");

        Path world = dir.resolve("world");
        WorldScenarios.writeFromPreset(preset, 4, world, file);
        String yaml = Files.readString(world.resolve("world.yaml"));
        assertTrue(yaml.contains("source: dem") && yaml.contains("centerLat") && yaml.contains("coreExtentM"), yaml);
        // compare at the preset's own window: drop the world's larger core
        yaml = yaml.replaceAll("(?m)^  coreExtentM: .*\\R", "")
                .replaceAll("(?m)^  halfExtent: .*$", "  halfExtent: " + real.halfExtentColumns());
        Files.writeString(world.resolve("world.yaml"), yaml);
        Scenario fromWorld = WorldScenarios.open(world, World.ChangePolicy.REJECT);
        assertEquals(scenario.engine().stateHash(), fromWorld.engine().stateHash());
        scenario.engine().runFor(5);
        fromWorld.engine().runFor(5);
        assertEquals(scenario.engine().stateHash(), fromWorld.engine().stateHash());
    }
}
