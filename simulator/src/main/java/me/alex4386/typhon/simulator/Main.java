package me.alex4386.typhon.simulator;

import me.alex4386.typhon.engine.config.ConfigException;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.engine.worlds.WorldDirectory;
import me.alex4386.typhon.simulator.scenario.WorldScenarios;

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
import me.alex4386.typhon.engine.save.DirectorySaveStore;
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
 * run --preset NAME [--seed N] [--hours H] [--out DIR] [--sample-seconds S] [--base-step-ms MS]
 *     [--save DIR] [--load DIR]
 *     [--skip-events Type,Type|none] [--dem FILE [--dem-cell M] [--dem-meters-per-block L]] [--quiet]
 * run --world DIR [--hours H] [--out DIR] [--sample-seconds S] [--accept-config-change | --reset-changed]
 *     [--no-save] [--skip-events ...] [--quiet]
 * init-world (--preset NAME [--seed N] | --example twin) --out DIR
 * </pre>
 */
public final class Main {
    /**
     * Event types left out of {@code events.ndjson} by default. The engine now aggregates its
     * high-volume telemetry (gas hazards, fumaroles, ash fall, lava solidification and ocean entry),
     * so nothing is skipped; pass {@code --skip-events A,B} to drop types from the log.
     */
    static final Set<String> DEFAULT_SKIPPED_EVENTS = Set.of();

    /** Options that take no value. */
    static final Set<String> FLAGS = Set.of("quiet", "accept-config-change", "reset-changed", "no-save");

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
                Map<String, String> options = parse(args);
                return options.containsKey("world") ? runWorld(options, out, err) : runPreset(options, out, err);
            }
            case "init-world" -> {
                return initWorld(parse(args), out, err);
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
            if (FLAGS.contains(key)) {
                options.put(key, "true");
            } else {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                options.put(key, args[++i]);
            }
        }
        return options;
    }

    static int initWorld(Map<String, String> options, PrintStream out, PrintStream err) {
        String target = options.get("out");
        if (target == null) {
            err.println("init-world needs --out DIR");
            return 1;
        }
        Path dir = Path.of(target);
        if (Files.exists(dir.resolve(WorldDirectory.WORLD_FILE))) {
            err.println(dir.resolve(WorldDirectory.WORLD_FILE) + " already exists");
            return 1;
        }
        if (options.containsKey("preset")) {
            Preset preset = Presets.get(options.get("preset"));
            long seed = Long.parseLong(options.getOrDefault("seed", "1"));
            WorldScenarios.writeFromPreset(preset, seed, dir);
            out.println("Wrote world '" + preset.name() + "' (seed " + seed + ") to " + dir);
        } else if (options.containsKey("example")) {
            WorldScenarios.writeExample(options.get("example"), dir);
            out.println("Wrote example world '" + options.get("example") + "' to " + dir);
        } else {
            err.println("init-world needs --preset NAME or --example " + WorldScenarios.examples());
            return 1;
        }
        out.println("Run it with: run --world " + dir);
        return 0;
    }

    static int runWorld(Map<String, String> options, PrintStream out, PrintStream err) throws IOException {
        Path worldDir = Path.of(options.get("world"));
        World.ChangePolicy policy = options.containsKey("reset-changed") ? World.ChangePolicy.RESET_CHANGED
                : options.containsKey("accept-config-change") ? World.ChangePolicy.ACCEPT : World.ChangePolicy.REJECT;
        Scenario scenario;
        try {
            scenario = WorldScenarios.open(worldDir, policy);
        } catch (ConfigException e) {
            err.println(e.getMessage());
            return 1;
        }
        World world = scenario.session();
        if (!world.changes().isEmpty()) out.println("Definition changes since the last save:" + world.changes());
        double hours = Double.parseDouble(options.getOrDefault("hours", "1"));
        double sampleSeconds = Double.parseDouble(options.getOrDefault("sample-seconds", "10"));
        Path dir = Path.of(options.getOrDefault("out", worldDir.resolve("runs").resolve("latest").toString()));
        Files.createDirectories(dir);
        Set<String> skipped = new HashSet<>(DEFAULT_SKIPPED_EVENTS);
        if (options.containsKey("skip-events")) {
            skipped.clear();
            String list = options.get("skip-events");
            if (!list.equals("none")) skipped.addAll(List.of(list.split(",")));
        }
        out.printf(Locale.ROOT, "%s world '%s' (%d volcanoes: %s) at t=%s for %s simulated hours → %s%n",
                scenario.restored() ? "Resuming" : "Starting", world.definition().name(), world.volcanoes().size(),
                String.join(", ", world.volcanoes().keySet()), time(scenario.engine().time()), fmt(hours), dir);
        Simulation.Result result;
        try (EventLog events = new EventLog(dir.resolve("events.ndjson"), skipped)) {
            Simulation simulation = new Simulation(scenario, sampleSeconds).onEvent(events);
            if (!options.containsKey("quiet")) simulation.onProgress(p -> progress(out, p), 2000);
            result = simulation.run(hours);
            out.printf(Locale.ROOT, "Wrote %d events%n", events.written());
        }
        CsvWriter.write(dir.resolve("timeseries.csv"), result.samples());
        Map<String, String> maps = new MapRenderer(scenario).writeAll(dir);
        ReportWriter.write(dir.resolve("report.html"), WorldScenarios.describe(scenario), result, maps, dir);
        if (!options.containsKey("no-save")) {
            scenario.saveWorld();
            out.printf(Locale.ROOT, "Saved world at t=%s to %s%n", time(scenario.engine().time()), worldDir.resolve("state"));
        }
        out.printf(Locale.ROOT, "Done: %d steps in %.1f s (%.0fx real time); report: %s%n", result.steps(),
                result.wallSeconds(), result.speedup(), dir.resolve("report.html"));
        for (Map.Entry<String, me.alex4386.typhon.engine.assembly.VolcanoSystem> v : world.volcanoes().entrySet()) {
            var chamber = v.getValue().chamber();
            out.printf(Locale.ROOT, "  %-10s P=%.2f MPa erupting=%s alert=%s%n", v.getKey(), chamber.overpressureMPa(),
                    chamber.erupting(), v.getValue().alert().level());
        }
        return 0;
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
        double baseStepMs = Double.parseDouble(options.getOrDefault("base-step-ms", "50"));
        Scenario.Options engineOptions = Scenario.Options.DEFAULT.withBaseStepMicros(Math.round(baseStepMs * 1000));
        if (options.containsKey("load")) {
            Path load = Path.of(options.get("load"));
            if (!Files.isRegularFile(load.resolve("meta.json"))) {
                err.println("No save found in " + load);
                return 1;
            }
            engineOptions = engineOptions.withRestore(new DirectorySaveStore(load));
        }
        Scenario scenario = preset.build(seed, terrain, engineOptions);
        if (scenario.restored()) {
            out.printf(Locale.ROOT, "Resumed %s at t=%s from %s%n", preset.name(), time(scenario.engine().time()),
                    options.get("load"));
        }

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

        if (options.containsKey("save")) {
            Path save = Path.of(options.get("save"));
            DirectorySaveStore store = new DirectorySaveStore(save);
            scenario.save(store);
            out.printf(Locale.ROOT, "Saved t=%s to %s (%d files written)%n", time(scenario.engine().time()), save,
                    store.writeCount());
        }

        Sample last = result.samples().get(result.samples().size() - 1);
        out.printf(Locale.ROOT, "Done: %d steps of %s ms in %.1f s (%.0f steps/s, %.0fx real time)%n",
                result.steps(), fmt(baseStepMs), result.wallSeconds(), result.stepsPerSecond(), result.speedup());
        out.printf(Locale.ROOT, "  eruptions=%d first=%s peakRate=%.3g m3/s erupted=%.3g m3 alert=%s style=%s%n",
                summary.eruptions, Double.isNaN(summary.firstEruptionSeconds) ? "none" : time(summary.firstEruptionSeconds),
                summary.peakEruptionRate, last.get("erupted_volume_m3"), last.alertLevel(), last.style());
        out.printf(Locale.ROOT, "  lava emitted=%.0f solidified=%.0f blocks, longest flow=%.0f m; plumeTop=%s; bombs=%d; quakes=%s%n",
                last.get("lava_emitted_blocks"), last.get("lava_solidified_blocks"), summary.maxFlowLengthM,
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
        out.printf(Locale.ROOT, "  t+%s (%4.1f%%) %.0f steps/s (%.0fx)%s%n", time(p.simulatedSeconds()),
                100.0 * p.simulatedSeconds() / p.totalSeconds(), p.stepsPerSecond(), p.speedup(), state);
    }

    private static void usage(PrintStream out) {
        out.println("Typhon headless simulator");
        out.println("  list-presets");
        out.println("  run --preset NAME [--seed N] [--hours H] [--out DIR] [--sample-seconds S]");
        out.println("      [--base-step-ms MS (default 50)] [--save DIR] [--load DIR]");
        out.println("      [--skip-events Type,Type|none] [--dem FILE [--dem-cell M] [--dem-meters-per-block L]");
        out.println("      [--dem-max-meters M (png)]] [--quiet]");
        out.println("  run --world DIR [--hours H] [--out DIR] [--sample-seconds S]");
        out.println("      [--accept-config-change | --reset-changed] [--no-save] [--quiet]");
        out.println("  init-world (--preset NAME [--seed N] | --example twin) --out DIR");
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
