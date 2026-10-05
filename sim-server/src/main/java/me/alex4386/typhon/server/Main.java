package me.alex4386.typhon.server;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;

/**
 * Command line entry point.
 *
 * <pre>
 * sim-server [--preset NAME[,NAME…] [--seed N]] [--world DIR[,DIR…]] [--port 8787] [--host 0.0.0.0]
 *            [--worlds-dir worlds] [--ui visualizer/dist] [--speed 20] [--base-step-ms 50] [--max-sessions 8]
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-h") || a.equals("--help")) {
                usage(out);
                return;
            }
            if (!a.startsWith("--") || i + 1 >= args.length) {
                System.err.println("Bad argument: " + a);
                usage(System.err);
                System.exit(2);
            }
            opts.put(a.substring(2), args[++i]);
        }
        if (opts.containsKey("threads")) {
            // must be set before any engine or tile store exists (see Parallel.defaultThreads)
            System.setProperty("typhon.threads", String.valueOf(Math.max(1, Integer.parseInt(opts.get("threads")))));
        }
        long seed = Long.parseLong(opts.getOrDefault("seed", "1"));
        Path worldsDir = Path.of(opts.getOrDefault("worlds-dir", "worlds"));
        Path ui = opts.containsKey("ui") ? Path.of(opts.get("ui")) : null;
        if (ui == null && Files.isDirectory(Path.of("visualizer/dist"))) ui = Path.of("visualizer/dist"); // repo root default
        if (ui != null && !Files.isDirectory(ui)) {
            System.err.println("--ui " + ui + " is not a directory (build the visualizer with npm run build)");
            System.exit(2);
        }
        // Initial sessions are optional and may be listed: --preset a,b --world worlds/x,worlds/y.
        List<String> presets = list(opts.get("preset"));
        List<String> worlds = list(opts.get("world"));
        for (String p : presets) Presets.get(p); // validate early
        String defaultPreset = presets.isEmpty() ? "kilauea" : presets.get(0);
        SimServer.Config config = new SimServer.Config(opts.getOrDefault("host", "0.0.0.0"),
                Integer.parseInt(opts.getOrDefault("port", "8787")), worldsDir, ui, defaultPreset, seed,
                Math.round(Double.parseDouble(opts.getOrDefault("base-step-ms", "50")) * 1000),
                Double.parseDouble(opts.getOrDefault("speed", "20")),
                Integer.parseInt(opts.getOrDefault("max-sessions", String.valueOf(SimServer.DEFAULT_MAX_SESSIONS))));
        SimServer server = new SimServer(config);
        for (String w : worlds) server.createWorld(Path.of(w));
        for (String p : presets) server.createPreset(p, seed);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        out.printf("Typhon sim-server listening on ws://%s:%d/ws (subprotocol %s)%n", config.host(), server.port(),
                SimServer.SUBPROTOCOL);
        out.printf("Worlds directory: %s (%d loaded; start or open more from the visualizer)%n",
                worldsDir.toAbsolutePath(), worlds.size() + presets.size());
        if (ui != null) out.printf("Visualizer: http://localhost:%d/%n", server.port());
        else out.printf("Visualizer: cd visualizer && npm run dev, then open http://localhost:5180/?server=ws://localhost:%d/ws%n",
                server.port());
        Thread.currentThread().join();
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split(",")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    private static void usage(PrintStream out) {
        out.println("Usage: sim-server [--preset NAME[,NAME…] [--seed N]] [--world DIR[,DIR…]] [--port 8787]");
        out.println("                  [--host 0.0.0.0] [--worlds-dir worlds] [--ui visualizer/dist] [--speed 20]");
        out.println("                  [--base-step-ms 50] [--max-sessions 8]");
        out.println("                  [--threads N (default: all cores; results are identical for any N)]");
        out.println("One server runs any number of worlds side by side (up to --max-sessions). --preset/--world only");
        out.println("load worlds at startup; more can be started, opened, paused and closed from the visualizer.");
        out.println("Presets:");
        for (Preset p : Presets.all()) out.println("  " + p.name() + " — " + p.title());
    }
}
