package me.alex4386.typhon.simulator.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import me.alex4386.typhon.simulator.MainAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputTest {
    @Test
    void cliWritesCsvEventsMapsAndReport(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = MainAccess.run(new String[] {
            "run", "--preset", "stromboli", "--hours", "1", "--seed", "3", "--sample-seconds", "60",
            "--out", dir.toString(), "--quiet"}, new PrintStream(out), System.err);
        assertEquals(0, code, out.toString());

        List<String> csv = Files.readAllLines(dir.resolve("timeseries.csv"));
        assertTrue(csv.get(0).startsWith("step,time_s,alert_level_name,style_name,overpressure_mpa"));
        // an hour of Stromboli: real bursts come every 10-20 min
        assertTrue(csv.size() >= 2, "a header and at least one sample: " + csv.size());
        int columns = csv.get(0).split(",").length;
        for (String line : csv) assertEquals(columns, line.split(",").length);

        List<String> events = Files.readAllLines(dir.resolve("events.ndjson"));
        assertTrue(events.size() > 10);
        boolean sawEruption = false;
        for (String line : events) {
            JsonObject e = JsonParser.parseString(line).getAsJsonObject();
            assertTrue(e.has("type") && e.has("time"), line);
            // Stromboli's open vent is active through slug bursts, not chamber eruptions.
            sawEruption |= e.get("type").getAsString().equals("ExplosiveBurst");
        }
        assertTrue(sawEruption, "explosions from the open vent");

        for (String map : List.of("map-elevation.png", "map-change.png", "map-lava.png", "map-ash.png", "map-geothermal.png",
                "section-ew.png", "section-columns.png")) {
            assertTrue(ImageIO.read(dir.resolve(map).toFile()).getWidth() > 100, map);
        }
        String html = Files.readString(dir.resolve("report.html"));
        assertTrue(html.contains("<svg") && html.contains("data:image/png;base64,"), "charts and embedded maps");
        assertTrue(html.contains("Stromboli"));
    }

    @Test
    void skippedEventTypesAreNotWritten(@TempDir Path dir) throws Exception {
        int code = MainAccess.run(new String[] {
            "run", "--preset", "kilauea", "--hours", "0.01", "--out", dir.toString(), "--quiet",
            "--skip-events", "SeismicEvent,FumaroleActivity"}, new PrintStream(new ByteArrayOutputStream()), System.err);
        assertEquals(0, code);
        for (String line : Files.readAllLines(dir.resolve("events.ndjson"))) {
            String type = JsonParser.parseString(line).getAsJsonObject().get("type").getAsString();
            assertTrue(!type.equals("SeismicEvent") && !type.equals("FumaroleActivity"), type);
        }
    }

    @Test
    void realPresetReportComparesWithReferenceValues(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = MainAccess.run(new String[] {
            "run", "--preset", "kilauea", "--hours", "0.02", "--out", dir.toString(), "--quiet"},
                new PrintStream(out), System.err);
        assertEquals(0, code, out.toString());
        String text = out.toString();
        assertTrue(text.contains("ref Summit elevation") && text.contains("WITHIN"), text);
        String html = Files.readString(dir.resolve("report.html"));
        assertTrue(html.contains("Reference vs model"));
        assertTrue(html.contains("Copernicus_DSM_COG_10_N19_00_W156_00_DEM.tif"), "DEM source documented");
    }

    @Test
    void demInfoPointsAtRealTiles() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, MainAccess.run(new String[] {"dem-info", "--preset", "st-helens"}, new PrintStream(out),
                System.err));
        assertTrue(out.toString().contains("Copernicus_DSM_COG_10_N46_00_W123_00_DEM"), out.toString());
        assertEquals(1, MainAccess.run(new String[] {"dem-info"}, new PrintStream(out),
                new PrintStream(out)));
    }

    @Test
    void listPresetsAndUsage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, MainAccess.run(new String[] {"list-presets"}, new PrintStream(out), System.err));
        assertTrue(out.toString().contains("kilauea") && out.toString().contains("yellowstone"));
        assertEquals(1, MainAccess.run(new String[] {"bogus"}, new PrintStream(out), new PrintStream(out)));
    }
}
