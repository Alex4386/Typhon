package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.alex4386.typhon.engine.save.DirectorySaveStore;
import me.alex4386.typhon.simulator.MainAccess;
import me.alex4386.typhon.simulator.run.Simulation;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveLoadTest {
    @Test
    void savedRunResumesIdentically(@TempDir Path dir) {
        Preset preset = Presets.get("kilauea");
        ColumnGrid terrain = preset.terrain(3);

        Scenario straight = preset.build(3, terrain);
        new Simulation(straight, 30).run(90 / 3600.0);

        Scenario first = preset.build(3, terrain);
        new Simulation(first, 30).run(60 / 3600.0);
        first.save(new DirectorySaveStore(dir));

        Scenario resumed = preset.build(3, terrain, Scenario.Options.DEFAULT.withRestore(new DirectorySaveStore(dir)));
        assertTrue(resumed.restored());
        assertEquals(60.0, resumed.engine().time(), 1e-9);
        new Simulation(resumed, 30).run(30 / 3600.0);

        // the state hash covers the world model (the terrain subsystem saves its layer stacks)
        assertEquals(straight.engine().stateHash(), resumed.engine().stateHash());
    }

    @Test
    void cliSavesAndLoads(@TempDir Path dir) throws Exception {
        Path save = dir.resolve("save");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertEquals(0, MainAccess.run(new String[] {"run", "--preset", "kilauea", "--hours", "0.01", "--quiet",
                "--base-step-ms", "100", "--out", dir.resolve("a").toString(), "--save", save.toString()}, out, out));
        assertTrue(Files.isRegularFile(save.resolve("meta.json")));
        assertEquals(0, MainAccess.run(new String[] {"run", "--preset", "kilauea", "--hours", "0.01", "--quiet",
                "--base-step-ms", "100", "--out", dir.resolve("b").toString(), "--load", save.toString()}, out, out));
        String log = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("Resumed kilauea at t=0:00:36"), log);
        // A different base step cannot resume the save.
        assertTrue(MainAccessCheck.fails(() -> MainAccess.run(new String[] {"run", "--preset", "kilauea", "--hours", "0.01",
                "--quiet", "--out", dir.resolve("c").toString(), "--load", save.toString()}, out, out)));
    }

    /** Small helper: whether running the action throws. */
    static final class MainAccessCheck {
        interface Action { int run() throws Exception; }

        static boolean fails(Action action) {
            try {
                action.run();
                return false;
            } catch (Exception e) {
                return true;
            }
        }
    }
}
