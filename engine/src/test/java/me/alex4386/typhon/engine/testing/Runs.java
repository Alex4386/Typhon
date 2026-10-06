package me.alex4386.typhon.engine.testing;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;

/** Time-based runs for tests: engines take steps of varying length, so tests run to times, not step counts. */
public final class Runs {
    private Runs() {}

    /** Steps until absolute time {@code time} (s); the frames. */
    public static List<EngineFrame> until(Engine engine, double time) {
        List<EngineFrame> frames = new ArrayList<>();
        while (engine.time() < time) frames.add(engine.step());
        return frames;
    }

    /**
     * Steps until an eruption starts (at most {@code maxWait} s), then {@code seconds} more; the
     * frames of both.
     */
    public static List<EngineFrame> runPastOnset(Engine engine, double maxWait, double seconds) {
        List<EngineFrame> frames = new ArrayList<>();
        double end = engine.time() + maxWait;
        while (engine.time() < end) {
            EngineFrame f = engine.step();
            frames.add(f);
            if (f.events().stream().anyMatch(e -> e instanceof EruptionStarted)) {
                frames.addAll(engine.runFor(seconds));
                break;
            }
        }
        return frames;
    }

    /** Time (s) of the first eruption onset in {@code frames}, or NaN. */
    public static double onset(List<EngineFrame> frames) {
        for (EngineFrame f : frames) for (EngineEvent e : f.events()) if (e instanceof EruptionStarted s) return s.time();
        return Double.NaN;
    }

    /** Frames starting at or after {@code micros}. */
    public static List<EngineFrame> from(List<EngineFrame> frames, long micros) {
        return frames.stream().filter(f -> f.timeMicros() >= micros).toList();
    }
}
