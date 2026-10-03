package me.alex4386.typhon.simulator.run;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.SimTime;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * Runs a scenario as fast as possible: ticks the engine, applies its block changes to the in-memory
 * world like a host would, forwards events and samples the time series.
 */
public final class Simulation {
    /** Progress callback, called roughly every {@code progressIntervalMillis} of wall time. */
    public record Progress(long tick, long totalTicks, double ticksPerSecond, Sample latest) {}

    private final Scenario scenario;
    private final long sampleTicks;
    private Consumer<EngineEvent> eventSink = e -> { };
    private Consumer<Progress> progress = p -> { };
    private long progressIntervalMillis = 2000;

    public Simulation(Scenario scenario, double sampleSeconds) {
        if (!(sampleSeconds > 0)) throw new IllegalArgumentException("sampleSeconds must be > 0");
        this.scenario = scenario;
        this.sampleTicks = Math.max(1, SimTime.secondsToTicks(sampleSeconds));
    }

    public Simulation onEvent(Consumer<EngineEvent> sink) {
        this.eventSink = sink;
        return this;
    }

    public Simulation onProgress(Consumer<Progress> listener, long intervalMillis) {
        this.progress = listener;
        this.progressIntervalMillis = intervalMillis;
        return this;
    }

    /** Result of {@link #run}. */
    public record Result(Scenario scenario, List<Sample> samples, RunSummary summary, long ticks, double wallSeconds) {
        public double ticksPerSecond() {
            return wallSeconds > 0 ? ticks / wallSeconds : Double.POSITIVE_INFINITY;
        }
    }

    public Result run(double hours) {
        long totalTicks = Math.max(1, SimTime.secondsToTicks(hours * 3600));
        List<Sample> samples = new ArrayList<>();
        RunSummary summary = new RunSummary();

        long start = System.nanoTime();
        long lastProgress = start;
        long lastProgressTick = 0;
        Sample latest = null;

        for (long i = 0; i < totalTicks; i++) {
            EngineFrame frame = scenario.engine().tick();
            for (BlockChange change : frame.blockChanges()) scenario.world().apply(change);
            for (EngineEvent event : frame.events()) {
                summary.observe(event);
                eventSink.accept(event);
            }
            if (i == 0) scenario.runAfterFirstTick();

            long tick = frame.tick();
            if (tick % sampleTicks == 0 || i == totalTicks - 1) {
                latest = Sample.capture(scenario, tick);
                samples.add(latest);
                summary.observe(latest);
            }

            long now = System.nanoTime();
            if ((now - lastProgress) / 1_000_000 >= progressIntervalMillis) {
                double rate = (i + 1 - lastProgressTick) / ((now - lastProgress) / 1e9);
                progress.accept(new Progress(i + 1, totalTicks, rate, latest));
                lastProgress = now;
                lastProgressTick = i + 1;
            }
        }

        double wall = (System.nanoTime() - start) / 1e9;
        return new Result(scenario, samples, summary, totalTicks, wall);
    }
}
