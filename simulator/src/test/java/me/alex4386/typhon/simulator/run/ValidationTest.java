package me.alex4386.typhon.simulator.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.ReferenceValue;
import me.alex4386.typhon.simulator.scenario.ReferenceValue.Verdict;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationTest {
    @Test
    void outcomesFollowVerdictsAndInformativeRowsNeverFail() {
        ReferenceValue ref = ReferenceValue.range("x", 1, 2, "m", "s", ReferenceValue.Metric.LONGEST_FLOW_M);
        assertEquals(Validation.Outcome.PASS, Validation.outcome(ref, Verdict.WITHIN));
        assertEquals(Validation.Outcome.OUT_OF_RANGE, Validation.outcome(ref, Verdict.ABOVE));
        assertEquals(Validation.Outcome.NOT_OBSERVED, Validation.outcome(ref, Verdict.NOT_OBSERVED));
        assertEquals(Validation.Outcome.INFO, Validation.outcome(ref.informative("why"), Verdict.ABOVE));
    }

    @Test
    void everyRealPresetHasReferenceValues() {
        List<Preset> real = Validation.realPresets();
        assertEquals(6, real.size(), real.stream().map(Preset::name).collect(Collectors.joining(",")));
        for (Preset p : real) assertTrue(p.referenceValues().size() >= 3, p.name());
    }

    @Test
    void shortRunWritesJsonMarkdownAndHtml(@TempDir Path dir) throws Exception {
        Validation.PresetResult r = Validation.run(Presets.get("stromboli-real"), 1, 60.0 / 3600, null, s -> { });
        assertFalse(r.checks().isEmpty());
        Validation.writeAll(dir, List.of(r));
        JsonObject json = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("validation.json")))
                .getAsJsonObject();
        assertEquals(1, json.getAsJsonArray("presets").size());
        assertTrue(Files.readString(dir.resolve("validation.md")).contains("stromboli-real"));
        assertTrue(Files.readString(dir.resolve("validation.html")).contains("<table>"));
    }

    /** The full suite: every real-scale preset at its reference horizon (~20 min). */
    @Test
    @Tag("validation")
    void realPresetsMatchObservations() throws Exception {
        Path out = Path.of(System.getProperty("typhon.validation.out", "build/validation"));
        List<Validation.PresetResult> results = new ArrayList<>();
        for (Preset p : Validation.realPresets()) {
            results.add(Validation.run(p, 1, Double.NaN, out.resolve(p.name()), System.out::println));
        }
        Validation.writeAll(out, results);
        String failures = results.stream().flatMap(r -> r.checks().stream().filter(Validation.Check::failed)
                        .map(c -> r.preset() + ": " + c.row().reference().quantity() + " = " + c.row().modelText()
                                + " (reference " + c.row().reference().referenceText() + ")"))
                .collect(Collectors.joining("\n"));
        assertTrue(failures.isEmpty(), "out of range:\n" + failures);
    }
}
