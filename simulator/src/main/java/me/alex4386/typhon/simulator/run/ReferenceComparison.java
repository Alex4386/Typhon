package me.alex4386.typhon.simulator.run;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.geothermal.HydrothermalFeature;
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
            // Styles estimated from what eruptions did; forecasts between eruptions do not count.
            case FINAL_STYLE -> result.summary().lastEstimatedStyle;
            case ANY_STYLE -> result.summary().firstStyleSeconds.keySet().stream()
                    .filter(st -> st.equalsIgnoreCase(ref.expected())).findFirst()
                    .orElse(result.summary().lastEstimatedStyle);
            case PHREATOMAGMATIC_SEQUENCE -> {
                RunSummary s = result.summary();
                boolean wet = !Double.isNaN(s.phreatomagmaticStartSeconds);
                if (wet && !Double.isNaN(s.lavaAfterPhreatomagmaticSeconds)) yield "SURTSEYAN→EFFUSIVE";
                if (wet) yield "SURTSEYAN";
                yield Double.isNaN(s.firstLavaFlowSeconds) ? null : "EFFUSIVE";
            }
            case FIRST_VENT_WATER_PHASE -> {
                RunSummary s = result.summary();
                double wet = s.phreatomagmaticStartSeconds;
                double lava = s.firstLavaFlowSeconds;
                if (!Double.isNaN(wet) && (Double.isNaN(lava) || wet <= lava)) yield "SURTSEYAN";
                yield Double.isNaN(lava) ? null : "EFFUSIVE";
            }
            default -> throw new IllegalArgumentException(ref.metric() + " is numeric");
        };
    }

    /**
     * Ash deposited downwind of the main vent (half-plane along the base wind direction) divided by the
     * deposit upwind; {@code NaN} without any deposit, capped at 1000 when nothing fell upwind.
     */
    static double ashDownwindRatio(Scenario scenario) {
        var tephra = scenario.volcano().tephra();
        if (tephra == null) return Double.NaN;
        double dir = tephra.wind().baseDirectionRad();
        double wx = Math.cos(dir);
        double wz = Math.sin(dir);
        var vent = scenario.volcano().vents().get(0).position();
        int half = scenario.initialTerrain().size() / 2;
        double down = 0;
        double up = 0;
        for (int z = -half; z < half; z += 4) {
            for (int x = -half; x < half; x += 4) {
                double t = tephra.depositThickness(x, z);
                if (!(t > 0)) continue;
                double along = (x - vent.x()) * wx + (z - vent.z()) * wz;
                if (along > 0) down += t;
                else if (along < 0) up += t;
            }
        }
        if (down + up <= 0) return Double.NaN;
        return up > 0 ? Math.min(1000, down / up) : 1000;
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
            // per hour of (persistently) erupting time
            case EXPLOSIONS_PER_HOUR -> result.simulatedSeconds() > 0
                    ? s.seismicCounts.getOrDefault(SeismicEventType.EXPLOSION, 0L) / (result.simulatedSeconds() / 3600)
                    : Double.NaN;
            case MAX_BALLISTIC_RANGE_M -> s.bombsLanded > 0 ? s.maxBombDistance * L : Double.NaN;
            case GEYSERS -> s.geysers;
            case ERUPTIONS -> s.eruptions;
            case SPRINGS -> s.featuresFormed.getOrDefault(HydrothermalFeature.HOT_SPRING, 0L)
                    + s.featuresFormed.getOrDefault(HydrothermalFeature.SULFUR_SPRING, 0L);
            case EXPLOSIONS_AFTER_DOME -> {
                Double dome = s.firstStyleSeconds.get("LAVA_DOME");
                if (dome == null) yield Double.NaN;
                long n = 0;
                for (double t : s.explosionTimes) if (t > dome) n++;
                yield n;
            }
            case ASH_DOWNWIND_RATIO -> ashDownwindRatio(scenario);
            case MAX_VEI -> s.maxVei < 0 ? Double.NaN : s.maxVei;
            case PHREATOMAGMATIC_HOURS -> Double.isNaN(s.phreatomagmaticStartSeconds) ? Double.NaN
                    : ((Double.isNaN(s.phreatomagmaticEndSeconds) ? result.simulatedSeconds() : s.phreatomagmaticEndSeconds)
                            - s.phreatomagmaticStartSeconds) / 3600;
            case FINAL_STYLE, ANY_STYLE, PHREATOMAGMATIC_SEQUENCE, FIRST_VENT_WATER_PHASE ->
                    throw new IllegalArgumentException(metric + " is categorical");
        };
    }

    static String fmt(double v) {
        if (Math.abs(v) >= 1e5 || (Math.abs(v) < 1e-2 && v != 0)) return String.format(java.util.Locale.ROOT, "%.3g", v);
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
