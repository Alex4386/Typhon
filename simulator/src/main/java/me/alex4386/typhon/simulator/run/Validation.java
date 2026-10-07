package me.alex4386.typhon.simulator.run;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import me.alex4386.typhon.simulator.output.CsvWriter;
import me.alex4386.typhon.simulator.output.MapRenderer;
import me.alex4386.typhon.simulator.output.ReportWriter;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.ReferenceValue;
import me.alex4386.typhon.simulator.scenario.ReferenceValue.Verdict;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * The validation suite: runs presets for their reference horizon ({@link Preset#validationHours()})
 * and judges every {@link ReferenceValue} against the run.
 *
 * <p>Outcomes: {@code PASS} (within range / matches), {@code OUT_OF_RANGE} (below, above or a
 * different category), {@code NOT_OBSERVED} (the run never produced the quantity) and {@code INFO}
 * (informative rows, shown for context only). Only {@code OUT_OF_RANGE} and {@code NOT_OBSERVED} on
 * non-informative rows fail the suite.
 */
public final class Validation {
    public enum Outcome { PASS, OUT_OF_RANGE, NOT_OBSERVED, INFO }

    public record Check(ReferenceComparison.Row row, Outcome outcome) {
        public boolean failed() {
            return outcome == Outcome.OUT_OF_RANGE || outcome == Outcome.NOT_OBSERVED;
        }
    }

    /** One preset's validation run. */
    public record PresetResult(String preset, String title, long seed, double hours, double wallSeconds,
            List<Check> checks, List<RunSummary.Eruption> eruptions, double buildSeconds, double peakHeapMB) {
        public long failures() {
            return checks.stream().filter(Check::failed).count();
        }
    }

    private Validation() {}

    public static Outcome outcome(ReferenceValue ref, Verdict verdict) {
        if (ref.informative()) return Outcome.INFO;
        return switch (verdict) {
            case WITHIN, MATCH -> Outcome.PASS;
            case BELOW, ABOVE, MISMATCH -> Outcome.OUT_OF_RANGE;
            case NOT_OBSERVED -> Outcome.NOT_OBSERVED;
        };
    }

    /** Presets: the ones with literature reference values. */
    public static List<Preset> realPresets() {
        return Presets.all().stream().filter(p -> p.realSetting() != null && !p.referenceValues().isEmpty()).toList();
    }

    /**
     * Runs one preset for {@code hours} (its {@link Preset#validationHours()} if {@code NaN}). With
     * {@code outDir}, the run's time series, maps and report are written there.
     */
    public static PresetResult run(Preset preset, long seed, double hours, Path outDir, Consumer<String> log)
            throws IOException {
        double h = Double.isNaN(hours) ? preset.validationHours() : hours;
        log.accept(String.format(Locale.ROOT, "validate %s for %s h ...", preset.name(), fmt(h)));
        System.gc();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
        long built = System.nanoTime();
        Scenario scenario = preset.build(seed);
        double buildSeconds = (System.nanoTime() - built) * 1e-9;
        Simulation.Result result = new Simulation(scenario, 10).run(h);
        double peakHeapMB = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum() / 1048576.0;
        log.accept(String.format(Locale.ROOT, "  build %.1f s, run %.1f s wall, peak heap %.0f MB (sum of pool peaks)",
                buildSeconds, result.wallSeconds(), peakHeapMB));
        List<Check> checks = new ArrayList<>();
        for (ReferenceComparison.Row row : ReferenceComparison.compare(preset, result)) {
            checks.add(new Check(row, outcome(row.reference(), row.verdict())));
        }
        if (outDir != null) {
            Files.createDirectories(outDir);
            CsvWriter.write(outDir.resolve("timeseries.csv"), result.samples());
            Map<String, String> maps = new MapRenderer(scenario).writeAll(outDir);
            ReportWriter.write(outDir.resolve("report.html"), preset, result, maps, outDir);
        }
        PresetResult r = new PresetResult(preset.name(), preset.title(), seed, h, result.wallSeconds(), checks,
                List.copyOf(result.summary().eruptionRecords), buildSeconds, peakHeapMB);
        for (Check c : checks) {
            log.accept(String.format(Locale.ROOT, "  %-13s %-30s %-24s model %s", c.outcome(),
                    c.row().reference().quantity(), c.row().reference().referenceText(), c.row().modelText()));
        }
        return r;
    }

    // ── Reports ──

    public static JsonObject json(List<PresetResult> results) {
        JsonObject root = new JsonObject();
        long failures = results.stream().mapToLong(PresetResult::failures).sum();
        root.addProperty("passed", failures == 0);
        root.addProperty("failures", failures);
        JsonArray presets = new JsonArray();
        for (PresetResult r : results) {
            JsonObject p = new JsonObject();
            p.addProperty("preset", r.preset());
            p.addProperty("seed", r.seed());
            p.addProperty("hours", r.hours());
            p.addProperty("wallSeconds", r.wallSeconds());
            p.addProperty("buildSeconds", r.buildSeconds());
            p.addProperty("peakHeapMB", r.peakHeapMB());
            p.addProperty("failures", r.failures());
            JsonArray checks = new JsonArray();
            for (Check c : r.checks()) {
                ReferenceValue ref = c.row().reference();
                JsonObject o = new JsonObject();
                o.addProperty("quantity", ref.quantity());
                o.addProperty("metric", ref.metric().name());
                o.addProperty("reference", ref.referenceText());
                if (!ref.categorical()) {
                    if (!Double.isNaN(ref.low())) o.addProperty("low", ref.low());
                    if (!Double.isNaN(ref.high())) o.addProperty("high", ref.high());
                    o.addProperty("unit", ref.unit());
                }
                o.addProperty("model", c.row().modelText());
                o.addProperty("verdict", c.row().verdict().name());
                o.addProperty("outcome", c.outcome().name());
                o.addProperty("source", ref.source());
                if (!ref.note().isEmpty()) o.addProperty("note", ref.note());
                checks.add(o);
            }
            p.add("checks", checks);
            JsonArray eruptions = new JsonArray();
            for (RunSummary.Eruption e : r.eruptions()) {
                JsonObject o = new JsonObject();
                o.addProperty("volcano", e.volcanoId());
                o.addProperty("startSeconds", e.startSeconds());
                if (!e.ongoing()) o.addProperty("endSeconds", e.endSeconds());
                o.addProperty("volumeM3", e.volumeM3());
                o.addProperty("styles", String.join(",", e.styles()));
                eruptions.add(o);
            }
            p.add("eruptions", eruptions);
            presets.add(p);
        }
        root.add("presets", presets);
        return root;
    }

    public static String markdown(List<PresetResult> results) {
        StringBuilder m = new StringBuilder("# Typhon validation\n\n");
        long failures = results.stream().mapToLong(PresetResult::failures).sum();
        m.append(failures == 0 ? "**All checks within reference ranges.**\n\n" : "**" + failures + " check(s) out of range or not observed.**\n\n");
        m.append("PASS = within the published range; OUT_OF_RANGE / NOT_OBSERVED fail; INFO rows are context only.\n");
        for (PresetResult r : results) {
            m.append("\n## ").append(r.preset()).append(String.format(Locale.ROOT, " (%s h, %s s wall)\n\n",
                    fmt(r.hours()), fmt(r.wallSeconds())));
            m.append("| quantity | reference | model | outcome | source |\n|---|---|---|---|---|\n");
            for (Check c : r.checks()) {
                ReferenceValue ref = c.row().reference();
                m.append("| ").append(ref.quantity()).append(" | ").append(ref.referenceText()).append(" | ")
                        .append(c.row().modelText()).append(" | ").append(c.outcome())
                        .append(ref.note().isEmpty() ? "" : " (" + ref.note() + ")").append(" | ").append(ref.source())
                        .append(" |\n");
            }
        }
        return m.toString();
    }

    public static String html(List<PresetResult> results) {
        StringBuilder h = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Typhon validation</title>"
                + "<style>body{font:14px/1.5 system-ui,sans-serif;margin:0 auto;max-width:1100px;padding:16px;color:#222}"
                + "table{border-collapse:collapse;margin:8px 0}td,th{padding:3px 10px;border-bottom:1px solid #ddd;text-align:left}"
                + "th{background:#eee}.PASS{color:#1e7e34}.OUT_OF_RANGE,.NOT_OBSERVED{color:#b00020;font-weight:600}"
                + ".INFO{color:#666}small{color:#666}</style></head><body><h1>Typhon validation</h1>");
        long failures = results.stream().mapToLong(PresetResult::failures).sum();
        h.append("<p>").append(failures == 0 ? "All checks within reference ranges."
                : failures + " check(s) out of range or not observed.").append(" INFO rows are context only.</p>");
        for (PresetResult r : results) {
            h.append("<h2>").append(esc(r.title())).append("</h2><p><small>").append(esc(r.preset()))
                    .append(" · seed ").append(r.seed()).append(" · ").append(fmt(r.hours())).append(" h · ")
                    .append(fmt(r.wallSeconds())).append(" s wall · <a href=\"").append(esc(r.preset()))
                    .append("/report.html\">full report</a></small></p>");
            h.append("<table><tr><th>quantity</th><th>reference</th><th>model</th><th>outcome</th><th>source</th></tr>");
            for (Check c : r.checks()) {
                ReferenceValue ref = c.row().reference();
                h.append("<tr><td>").append(esc(ref.quantity())).append("</td><td>").append(esc(ref.referenceText()))
                        .append("</td><td>").append(esc(c.row().modelText())).append("</td><td class=\"")
                        .append(c.outcome()).append("\">").append(c.outcome())
                        .append(ref.note().isEmpty() ? "" : "<br><small>" + esc(ref.note()) + "</small>")
                        .append("</td><td><small>").append(esc(ref.source())).append("</small></td></tr>");
            }
            h.append("</table>");
        }
        return h.append("</body></html>").toString();
    }

    /** Writes {@code validation.json}, {@code validation.md} and {@code validation.html} into {@code dir}. */
    public static void writeAll(Path dir, List<PresetResult> results) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("validation.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(json(results)));
        Files.writeString(dir.resolve("validation.md"), markdown(results));
        Files.writeString(dir.resolve("validation.html"), html(results));
    }

    static String fmt(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
