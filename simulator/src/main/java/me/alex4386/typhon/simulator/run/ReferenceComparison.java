package me.alex4386.typhon.simulator.run;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.seismic.SeismicEventType;
import me.alex4386.typhon.engine.terrain.TerrainChunkView;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.ReferenceValue;
import me.alex4386.typhon.simulator.scenario.ReferenceValue.Verdict;
import me.alex4386.typhon.simulator.scenario.Scenario;

/** Measures a run against its preset's {@link ReferenceValue}s ("reference vs model"). */
public final class ReferenceComparison {
    /** One compared row. {@code modelText} is the measured value formatted with the reference's unit. */
    public record Row(ReferenceValue reference, String modelText, Verdict verdict) {}

    private ReferenceComparison() {}

    public static List<Row> compare(Preset preset, Simulation.Result result) {
        List<Row> rows = new ArrayList<>();
        for (ReferenceValue ref : preset.referenceValues()) {
            if (ref.categorical()) {
                String model = categorical(ref, result);
                rows.add(new Row(ref, model == null ? "–" : model, ref.judge(model)));
            } else {
                double model = numeric(ref.metric(), result);
                rows.add(new Row(ref, Double.isNaN(model) ? "–" : fmt(model) + (ref.unit().isEmpty() ? "" : " " + ref.unit()),
                        ref.judge(model)));
            }
        }
        return rows;
    }

    static String categorical(ReferenceValue ref, Simulation.Result result) {
        List<Sample> samples = result.samples();
        return switch (ref.metric()) {
            case FINAL_STYLE -> samples.isEmpty() ? null : samples.get(samples.size() - 1).style();
            case ANY_STYLE -> {
                String last = null;
                for (Sample s : samples) {
                    if (s.style() == null) continue;
                    if (s.style().equalsIgnoreCase(ref.expected())) yield s.style();
                    last = s.style();
                }
                yield last;
            }
            default -> throw new IllegalArgumentException(ref.metric() + " is numeric");
        };
    }

    /** The measured value of a numeric metric (real units), {@code NaN} if not observed. */
    public static double numeric(ReferenceValue.Metric metric, Simulation.Result result) {
        Scenario scenario = result.scenario();
        VolcanoScaling scaling = scenario.volcano().scaling();
        double L = scaling.metersPerBlock();
        RunSummary s = result.summary();
        List<Sample> samples = result.samples();
        return switch (metric) {
            case SUMMIT_ELEVATION_M -> (scenario.initialTerrain().maxGround() + 1) * L;
            case FINAL_MAX_ELEVATION_M -> {
                int max = Integer.MIN_VALUE;
                for (TerrainChunkView c : scenario.terrain().chunks()) {
                    for (int i = 0; i < 256; i++) max = Math.max(max, c.groundY(i));
                }
                yield max == Integer.MIN_VALUE ? Double.NaN : (max + 1) * L;
            }
            case PEAK_ERUPTION_RATE_M3S -> s.peakEruptionRate > 0 ? s.peakEruptionRate : Double.NaN;
            case ERUPTED_VOLUME_M3 -> s.eruptionRecords.isEmpty() ? Double.NaN : s.totalEruptedVolume();
            case PLUME_TOP_KM -> s.maxPlumeTopY == Integer.MIN_VALUE ? Double.NaN
                    : (s.maxPlumeTopY + 1) * scaling.plumeMetersPerBlock() / 1000;
            case LONGEST_FLOW_M -> s.maxFlowLengthM > 0 ? s.maxFlowLengthM : Double.NaN;
            case EXPLOSIONS_PER_HOUR -> result.simulatedSeconds() > 0
                    ? s.seismicCounts.getOrDefault(SeismicEventType.EXPLOSION, 0L) / (result.simulatedSeconds() / 3600)
                    : Double.NaN;
            case MAX_BALLISTIC_RANGE_M -> s.bombsLanded > 0 ? s.maxBombDistance * L : Double.NaN;
            case GEYSERS -> s.geysers;
            case FINAL_STYLE, ANY_STYLE -> throw new IllegalArgumentException(metric + " is categorical");
        };
    }

    static String fmt(double v) {
        if (Math.abs(v) >= 1e5 || (Math.abs(v) < 1e-2 && v != 0)) return String.format(java.util.Locale.ROOT, "%.3g", v);
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
