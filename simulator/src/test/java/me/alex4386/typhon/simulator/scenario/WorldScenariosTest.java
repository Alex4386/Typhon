package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.simulator.MainAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Execution(ExecutionMode.CONCURRENT)
class WorldScenariosTest {
    static Stream<String> presets() {
        return Presets.all().stream().map(Preset::name);
    }

    @ParameterizedTest
    @MethodSource("presets")
    void presetWorldTemplateReproducesThePresetExactly(String name, @TempDir Path dir) {
        Preset preset = Presets.get(name);
        WorldScenarios.writeFromPreset(preset, 3, dir);
        assertTrue(Files.exists(dir.resolve("world.yaml")));

        Scenario fromPreset = preset.build(3);
        Scenario fromWorld = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        assertEquals(fromPreset.engine().stateHash(), fromWorld.engine().stateHash(),
                "the YAML round trip must reproduce every parameter");
        fromPreset.engine().runFor(10);
        fromWorld.engine().runFor(10);
        assertEquals(fromPreset.engine().stateHash(), fromWorld.engine().stateHash(), "and run identically");
    }

    private static String run(String... args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = MainAccess.run(args, new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8) + errors.toString(StandardCharsets.UTF_8);
        return code + "\n" + out;
    }

    @Test
    void twinExampleRunsSavesResumesAndGuardsDefinitionChanges(@TempDir Path dir) throws Exception {
        Path world = dir.resolve("twin");
        assertTrue(run("init-world", "--example", "twin", "--out", world.toString()).startsWith("0\n"));
        assertTrue(Files.exists(world.resolve("volcanoes/east.yaml")));
        assertTrue(Files.exists(world.resolve("volcanoes/west.yaml")));

        String first = run("run", "--world", world.toString(), "--hours", "0.02", "--quiet", "--out",
                dir.resolve("out1").toString());
        assertTrue(first.startsWith("0\n"), first);
        assertTrue(first.contains("Starting world 'twin' (2 volcanoes: east, west)"), first);
        assertTrue(Files.exists(world.resolve("state/meta.json")));
        assertTrue(Files.exists(world.resolve("state/world.json")));
        assertTrue(Files.exists(dir.resolve("out1/report.html")));

        String second = run("run", "--world", world.toString(), "--hours", "0.01", "--quiet", "--out",
                dir.resolve("out2").toString());
        assertTrue(second.contains("Resuming world 'twin'"), second);
        assertTrue(second.contains("at t=0:01:12"), "continues where the first run stopped: " + second);

        Path west = world.resolve("volcanoes/west.yaml");
        Files.writeString(west, Files.readString(west).replace("volume: 5.0E9", "volume: 6.0E9"));
        String rejected = run("run", "--world", world.toString(), "--hours", "0.01", "--quiet", "--no-save");
        assertTrue(rejected.startsWith("1\n"), rejected);
        assertTrue(rejected.contains("volcano:west magma.chamber.volume"), rejected);

        String reset = run("run", "--world", world.toString(), "--hours", "0.01", "--quiet", "--reset-changed",
                "--out", dir.resolve("out3").toString());
        assertTrue(reset.startsWith("0\n"), reset);
        assertTrue(reset.contains("Definition changes since the last save"), reset);
    }
}
