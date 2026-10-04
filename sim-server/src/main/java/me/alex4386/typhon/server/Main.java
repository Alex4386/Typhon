package me.alex4386.typhon.server;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;

/**
 * Command line entry point.
 *
 * <pre>
 * sim-server [--preset NAME [--seed N] | --world DIR] [--port 8787] [--host 0.0.0.0]
 *            [--worlds-dir worlds] [--ui visualizer/dist] [--speed 20] [--base-step-ms 50]
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
        String preset = opts.getOrDefault("preset", "kilauea");
        long seed = Long.parseLong(opts.getOrDefault("seed", "1"));
        Path worldsDir = Path.of(opts.getOrDefault("worlds-dir", "worlds"));
        Path ui = opts.containsKey("ui") ? Path.of(opts.get("ui")) : null;
        if (ui != null && !Files.isDirectory(ui)) {
            System.err.println("--ui " + ui + " is not a directory (build the visualizer with npm run build)");
            System.exit(2);
        }
        Presets.get(preset); // validate early
        SimServer.Config config = new SimServer.Config(opts.getOrDefault("host", "0.0.0.0"),
                Integer.parseInt(opts.getOrDefault("port", "8787")), worldsDir, ui, preset, seed,
                Math.round(Double.parseDouble(opts.getOrDefault("base-step-ms", "50")) * 1000),
                Double.parseDouble(opts.getOrDefault("speed", "20")));
        SimServer server = new SimServer(config);
        if (opts.containsKey("world")) server.createWorld(Path.of(opts.get("world")));
        else server.createPreset(preset, seed);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        out.printf("Typhon sim-server listening on ws://%s:%d/ws (subprotocol %s)%n", config.host(), server.port(),
                SimServer.SUBPROTOCOL);
        if (ui != null) out.printf("Visualizer: http://localhost:%d/%n", server.port());
        else out.printf("Visualizer: cd visualizer && npm run dev, then open http://localhost:5180/?server=ws://localhost:%d/ws%n",
                server.port());
        Thread.currentThread().join();
    }

    private static void usage(PrintStream out) {
        out.println("Usage: sim-server [--preset NAME [--seed N] | --world DIR] [--port 8787] [--host 0.0.0.0]");
        out.println("                  [--worlds-dir worlds] [--ui visualizer/dist] [--speed 20] [--base-step-ms 50]");
        out.println("Presets:");
        for (Preset p : Presets.all()) out.println("  " + p.name() + " — " + p.title());
    }
}
