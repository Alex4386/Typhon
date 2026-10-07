package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import me.alex4386.typhon.simulator.output.CsvWriter;
import me.alex4386.typhon.simulator.run.Simulation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Execution(ExecutionMode.CONCURRENT)
class PresetsTest {
    static Stream<String> presetNames() {
        return Presets.all().stream().map(Preset::name);
    }

    private static Simulation.Result run(String preset, long seed, double hours) {
        return new Simulation(Presets.get(preset).build(seed), 5).run(hours);
    }

    @Test
    void presetsAreDocumented() {
        assertEquals(6, Presets.all().size(), "six real-scale presets");
        for (Preset p : Presets.all()) {
            assertFalse(p.description().isBlank(), p.name());
            assertTrue(p.references().size() >= 3, p.name() + " should cite reference values");
            assertTrue(p.defaultHours() > 0);
            assertTrue(p.realSetting() != null, p.name() + " should describe its real setting");
            assertFalse(p.referenceValues().isEmpty(), p.name() + " should list reference values for the report");
        }
    }

    @ParameterizedTest
    @MethodSource("presetNames")
    void presetRunsShortHorizonDeterministically(String name) {
        Simulation.Result a = run(name, 42, 60 / 3600.0);
        Simulation.Result b = run(name, 42, 60 / 3600.0);
        assertTrue(a.steps() >= 1 && a.steps() <= 1200, "ran one minute in at most 1200 steps of 50 ms: " + a.steps());
        assertEquals(60.0, a.simulatedSeconds(), 1e-9);
        assertEquals(CsvWriter.toString(a.samples()), CsvWriter.toString(b.samples()), "same seed, same time series");
        assertEquals(a.summary().eventCounts, b.summary().eventCounts);
    }

    @Test
    void seedChangesTheRun() {
        String a = CsvWriter.toString(run("stromboli", 1, 60 / 3600.0).samples());
        String b = CsvWriter.toString(run("stromboli", 2, 60 / 3600.0).samples());
        assertNotEquals(a, b);
    }

    @Test
    void eruptivePresetsErupt() {
        for (String name : List.of("kilauea", "st-helens", "pinatubo", "surtsey")) {
            Simulation.Result r = run(name, 1, 60 / 3600.0);
            assertEquals(1, r.summary().eruptions, name + " should erupt within a minute");
        }
        // Stromboli: persistent explosions through the open conduit, no chamber eruption needed (real
        // Stromboli explodes every 10-20 minutes).
        Simulation.Result stromboli = run("stromboli", 1, 2);
        assertTrue(!stromboli.summary().explosionTimes.isEmpty(), "stromboli should be exploding within two hours");
        assertEquals(0, run("yellowstone", 1, 60 / 3600.0).summary().eruptions);
    }

    @Test
    void stylesMatchTheirVolcanoes() {
        var kilauea = run("kilauea", 1, 60 / 3600.0);
        assertEquals("HAWAIIAN", last(kilauea).style());
        assertTrue(last(kilauea).get("effusing") > 0);
        // real Stromboli bursts every 10-20 min: within an hour the open vent shows Strombolian activity
        var stromboli = run("stromboli", 1, 1);
        assertTrue(stromboli.samples().stream().anyMatch(s -> s.style().equals("STROMBOLIAN")), "Strombolian bursts");
        var pinatubo = run("pinatubo", 1, 60 / 3600.0);
        assertEquals("PLINIAN", last(pinatubo).style());
        assertTrue(pinatubo.summary().maxPlumeTopZ > 20_000, "Plinian column reaches the stratosphere: "
                + pinatubo.summary().maxPlumeTopZ + " m");
    }

    @Test
    @Tag("slow")
    void everyPresetAtDefaultLength() {
        for (Preset p : Presets.all()) {
            Simulation.Result r = new Simulation(p.build(1), 30).run(p.defaultHours());
            System.out.printf("%s: %.1f s wall, %.0f ticks/s, eruptions=%d, peak=%.3g m3/s%n", p.name(),
                    r.wallSeconds(), r.stepsPerSecond(), r.summary().eruptions, r.summary().peakEruptionRate);
        }
    }

    private static me.alex4386.typhon.simulator.run.Sample last(Simulation.Result r) {
        return r.samples().get(r.samples().size() - 1);
    }
}
