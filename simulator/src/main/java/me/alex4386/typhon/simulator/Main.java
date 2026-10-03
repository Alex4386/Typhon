package me.alex4386.typhon.simulator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.alex4386.typhon.simulator.output.CsvWriter;
import me.alex4386.typhon.simulator.output.EventLog;
import me.alex4386.typhon.simulator.output.MapRenderer;
import me.alex4386.typhon.simulator.output.ReportWriter;
import me.alex4386.typhon.simulator.run.RunSummary;
import me.alex4386.typhon.simulator.run.Sample;
import me.alex4386.typhon.simulator.run.Simulation;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.Scenario;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.terrain.DemImporter;

/**
 * Command line entry point.
 *
 * <pre>
 * list-presets
 * run --preset NAME [--seed N] [--hours H] [--out DIR] [--sample-seconds S]
 *     [--skip-events Type,Type|none] [--dem FILE [--dem-cell M] [--dem-meters-per-block L]] [--quiet]
 * </pre>
 */
public final class Main {
    /**
     * Per-step lava telemetry that would dominate the log: LavaSolidified fires every tick and
     * LavaEnteredWater currently re-fires for tiny films every step (~30 events/tick in the Surtsey
     * preset). Still counted in the report; pass {@code --skip-events none} to write everything.
     * (Gas hazards, fumarole activity and ash fall are aggregated by the engine and written.)
     */
    static final Set<String> DEFAULT_SKIPPED_EVENTS = Set.of("LavaSolidified", "LavaEnteredWater");

    private Main() {}

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            usage(out);
            return args.length == 0 ? 1 : 0;
        }
        switch (args[0]) {
            case "list-presets" -> {
                for (Preset p : Presets.all()) {
                    out.printf(Locale.ROOT, "%-12s %s (default %s h)%n", p.name(), p.title(), fmt(p.defaultHours()));
                }
                return 0;
            }
            case "run" -> {
                return runPreset(parse(args), out, err);
            }
            default -> {
                err.println("Unknown command: " + args[0]);
                usage(err);
                return 1;
            }
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + arg);
            String key = arg.substring(2);
            if (key.equals("quiet")) {
                options.put(key, "true");
            } else {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                options.put(key, args[++i]);
            }
        }
        return options;
    }

    static int runPreset(Map<String, String> options, PrintStream out, PrintStream err) throws IOException {
        String name = options.get("preset");
        if (name == null) {
            err.println("run needs --preset (see list-presets)");
            return 1;
        }
        Preset preset = Presets.get(name);
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));
        double hours = Double.parseDouble(options.getOrDefault("hours", Double.toString(preset.defaultHours())));
        double sampleSeconds = Double.parseDouble(options.getOrDefault("sample-seconds", "10"));
        Path dir = Path.of(options.getOrDefault("out", "sim-out/" + name + "-" + seed));
        boolean quiet = options.containsKey("quiet");
        Set<String> skipped = new HashSet<>(DEFAULT_SKIPPED_EVENTS);
        if (options.containsKey("skip-events")) {
            skipped.clear();
            String list = options.get("skip-events");
            if (!list.equals("none")) skipped.addAll(List.of(list.split(",")));
        }
        Files.createDirectories(dir);

        ColumnGrid terrain = preset.terrain(seed);
        if (options.containsKey("dem")) {
            Path dem = Path.of(options.get("dem"));
            double cell = Double.parseDouble(options.getOrDefault("dem-cell", "30"));
            double mpb = Double.parseDouble(options.getOrDefault("dem-meters-per-block", "8"));
            DemImporter.Dem data = dem.toString().toLowerCase(Locale.ROOT).endsWith(".png")
                    ? DemImporter.readPng(dem, 0, Double.parseDouble(options.getOrDefault("dem-max-meters", "3000")), cell)
                    : DemImporter.readAscii(dem, cell);
            terrain = DemImporter.toGrid(data, mpb, 384);
            out.println("Terrain replaced by DEM " + dem + " (" + terrain.size() + "x" + terrain.size() + " blocks)");
        }
        Scenario scenario = preset.build(seed, terrain);

        out.printf(Locale.ROOT, "Running %s (seed %d) for %s simulated hours → %s%n", preset.name(), seed, fmt(hours), dir);
        RunSummary summary;
        Simulation.Result result;
        try (EventLog events = new EventLog(dir.resolve("events.ndjson"), skipped)) {
            Simulation simulation = new Simulation(scenario, sampleSeconds).onEvent(events);
            if (!quiet) simulation.onProgress(p -> progress(out, p), 2000);
            result = simulation.run(hours);
            summary = result.summary();
            out.printf(Locale.ROOT, "Wrote %d events%n", events.written());
        }

        CsvWriter.write(dir.resolve("timeseries.csv"), result.samples());
        Map<String, String> maps = new MapRenderer(scenario).writeAll(dir);
        ReportWriter.write(dir.resolve("report.html"), preset, result, maps, dir);

        Sample last = result.samples().get(result.samples().size() - 1);
        out.printf(Locale.ROOT, "Done: %s ticks in %.1f s (%.0f ticks/s, %.0fx real time)%n",
                result.ticks(), result.wallSeconds(), result.ticksPerSecond(), result.ticksPerSecond() / 20);
        out.printf(Locale.ROOT, "  eruptions=%d first=%s peakRate=%.3g m3/s erupted=%.3g m3 alert=%s style=%s%n",
                summary.eruptions, Double.isNaN(summary.firstEruptionSeconds) ? "none" : time(summary.firstEruptionSeconds),
                summary.peakEruptionRate, last.get("erupted_volume_m3"), last.alertLevel(), last.style());
        out.printf(Locale.ROOT, "  lava emitted=%.0f solidified=%.0f blocks, longest flow=%.0f blocks; plumeTop=%s; bombs=%d; quakes=%s%n",
                last.get("lava_emitted_blocks"), last.get("lava_solidified_blocks"), summary.maxFlowLength,
                summary.maxPlumeTopY == Integer.MIN_VALUE ? "none" : Integer.toString(summary.maxPlumeTopY),
                summary.bombsLaunched, summary.seismicCounts);
        if (!summary.featuresFormed.isEmpty()) out.println("  hydrothermal features: " + summary.featuresFormed);
        out.println("Report: " + dir.resolve("report.html"));
        return 0;
    }

    private static void progress(PrintStream out, Simulation.Progress p) {
        Sample s = p.latest();
        String state = s == null ? "" : String.format(Locale.ROOT, " P=%.2f MPa rate=%.3g m3/s %s/%s",
                s.get("overpressure_mpa"), s.get("eruption_rate_m3s"), s.alertLevel(), s.style());
        out.printf(Locale.ROOT, "  t=%s (%4.1f%%) %.0f ticks/s%s%n", time(p.tick() / 20.0),
                100.0 * p.tick() / p.totalTicks(), p.ticksPerSecond(), state);
    }

    private static void usage(PrintStream out) {
        out.println("Typhon headless simulator");
        out.println("  list-presets");
        out.println("  run --preset NAME [--seed N] [--hours H] [--out DIR] [--sample-seconds S]");
        out.println("      [--skip-events Type,Type|none] [--dem FILE [--dem-cell M] [--dem-meters-per-block L]");
        out.println("      [--dem-max-meters M (png)]] [--quiet]");
        out.println("  events.ndjson omits " + DEFAULT_SKIPPED_EVENTS + " unless --skip-events is given.");
    }

    private static String time(double seconds) {
        long s = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
