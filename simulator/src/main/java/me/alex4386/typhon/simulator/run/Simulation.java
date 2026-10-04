package me.alex4386.typhon.simulator.run;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.sim.SimTime;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * Runs a scenario as fast as possible: steps the engine, applies its block changes to the in-memory
 * world like a host would, forwards events and samples the time series.
 */
public final class Simulation {
    /** Progress callback, called roughly every {@code progressIntervalMillis} of wall time. */
    public record Progress(double simulatedSeconds, double totalSeconds, double stepsPerSecond, double speedup,
            Sample latest) {}

    private final Scenario scenario;
    private final double sampleSeconds;
    private Consumer<EngineEvent> eventSink = e -> { };
    private Consumer<Progress> progress = p -> { };
    private long progressIntervalMillis = 2000;

    public Simulation(Scenario scenario, double sampleSeconds) {
        if (!(sampleSeconds > 0)) throw new IllegalArgumentException("sampleSeconds must be > 0");
        this.scenario = scenario;
        this.sampleSeconds = sampleSeconds;
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

    /**
     * Result of {@link #run}.
     *
     * @param steps engine steps run
     * @param simulatedSeconds simulated time covered by this run
     */
    public record Result(Scenario scenario, List<Sample> samples, RunSummary summary, long steps,
            double simulatedSeconds, double wallSeconds) {
        public double stepsPerSecond() {
            return wallSeconds > 0 ? steps / wallSeconds : Double.POSITIVE_INFINITY;
        }

        /** Simulated seconds per wall-clock second. */
        public double speedup() {
            return wallSeconds > 0 ? simulatedSeconds / wallSeconds : Double.POSITIVE_INFINITY;
        }
    }

    /** Runs {@code hours} of simulated time from wherever the engine currently is. */
    public Result run(double hours) {
        Engine engine = scenario.engine();
        long base = engine.baseStepMicros();
        long totalSteps = Math.max(1, (long) Math.ceil(SimTime.micros(hours * 3600) / (double) base));
        double totalSeconds = SimTime.seconds(totalSteps * base);
        List<Sample> samples = new ArrayList<>();
        RunSummary summary = new RunSummary();

        long start = System.nanoTime();
        long lastProgress = start;
        long lastProgressStep = 0;
        Sample latest = null;
        boolean fresh = !scenario.restored() && engine.currentStep() == 0;

        for (long i = 0; i < totalSteps; i++) {
            EngineFrame frame = engine.step();
            for (BlockChange change : frame.blockChanges()) scenario.world().apply(change);
            for (EngineEvent event : frame.events()) {
                summary.observe(event);
                eventSink.accept(event);
            }
            if (i == 0 && fresh) scenario.runAfterFirstTick();

            // Sample at the start of each step whose start time is a multiple of the sample period.
            if (SimTime.crossed(frame.timeMicros(), base, sampleSeconds) || i == 0 || i == totalSteps - 1) {
                latest = Sample.capture(scenario, frame);
                samples.add(latest);
                summary.observe(latest);
            }

            long now = System.nanoTime();
            if ((now - lastProgress) / 1_000_000 >= progressIntervalMillis) {
                double wall = (now - lastProgress) / 1e9;
                double rate = (i + 1 - lastProgressStep) / wall;
                progress.accept(new Progress(SimTime.seconds((i + 1) * base), totalSeconds, rate,
                        rate * SimTime.seconds(base), latest));
                lastProgress = now;
                lastProgressStep = i + 1;
            }
        }

        double wall = (System.nanoTime() - start) / 1e9;
        return new Result(scenario, samples, summary, totalSteps, totalSeconds, wall);
    }
}
