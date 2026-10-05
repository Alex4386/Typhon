package me.alex4386.typhon.simulator.run;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import me.alex4386.typhon.engine.assembly.SurfaceEvents.PhreatomagmaticChanged;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.alert.AlertEvents.AlertLevelChanged;
import me.alex4386.typhon.engine.alert.AlertEvents.EruptionStyleEstimated;
import me.alex4386.typhon.engine.geothermal.GeyserFormed;
import me.alex4386.typhon.engine.geothermal.HydrothermalFeature;
import me.alex4386.typhon.engine.geothermal.HydrothermalFeatureFormed;
import me.alex4386.typhon.engine.lava.LavaEvents.LavaOceanEntry;
import me.alex4386.typhon.engine.lava.LavaEvents.LavaFlowFront;
import me.alex4386.typhon.engine.lava.LavaEvents.TerrainNeeded;
import me.alex4386.typhon.engine.magma.MagmaEvents.ChamberSample;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionEnded;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.seismic.SeismicEventType;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLanded;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLaunched;
import me.alex4386.typhon.engine.tephra.TephraEvents.PlumeColumn;
import me.alex4386.typhon.engine.tephra.TephraEvents.VolcanicLightning;

/** Headline facts about a run, accumulated from the event stream. */
public final class RunSummary {
    /** A notable moment, for the report timeline. */
    public record Milestone(double timeSeconds, String description) {}

    /**
     * One eruption of one volcano.
     *
     * @param endSeconds end time, {@code NaN} if still erupting when the run finished
     * @param volumeM3 erupted DRE volume (m³; so far, for an ongoing eruption)
     * @param styles eruption styles estimated while it lasted, in order
     */
    public record Eruption(String volcanoId, double startSeconds, double endSeconds, double volumeM3, String startCause,
            List<String> styles) {
        public boolean ongoing() {
            return Double.isNaN(endSeconds);
        }
    }

    private record Open(double start, String cause, Set<String> styles) {}

    private final Map<String, Open> open = new HashMap<>();
    private final Map<String, String> currentStyle = new HashMap<>();
    /** Finished eruptions in end order; ongoing ones are appended by {@link #finish}. */
    public final List<Eruption> eruptionRecords = new ArrayList<>();
    /** Times of explosion quakes (s), for sequence metrics. */
    public final List<Double> explosionTimes = new ArrayList<>();
    /** Last style estimated during an eruption ({@code null} if none). */
    public String lastEstimatedStyle;
    /** Highest VEI estimated during an eruption (−1 if none). */
    public int maxVei = -1;
    /** First time each eruption style was estimated during an eruption (s). */
    public final Map<String, Double> firstStyleSeconds = new TreeMap<>();
    /** Start and end (s) of the first phreatomagmatic phase; {@code NaN} if not seen. */
    public double phreatomagmaticStartSeconds = Double.NaN;
    public double phreatomagmaticEndSeconds = Double.NaN;
    /** First lava flow-front report (s), {@code NaN} if lava never flowed. */
    public double firstLavaFlowSeconds = Double.NaN;
    /** First lava flow-front report after the first phreatomagmatic phase ended (s). */
    public double lavaAfterPhreatomagmaticSeconds = Double.NaN;

    public final Map<String, Long> eventCounts = new TreeMap<>();
    public final List<Milestone> milestones = new ArrayList<>();
    public final Map<SeismicEventType, Long> seismicCounts = new EnumMap<>(SeismicEventType.class);
    public final Map<HydrothermalFeature, Long> featuresFormed = new EnumMap<>(HydrothermalFeature.class);

    public double firstEruptionSeconds = Double.NaN;
    public int eruptions;
    public double peakEruptionRate;
    public double peakEruptionRateSeconds = Double.NaN;
    public double maxMagnitude = Double.NEGATIVE_INFINITY;
    public int maxPlumeTopY = Integer.MIN_VALUE;
    public double maxPlumeMassRate;
    public double maxFlowLengthM; // real metres
    public long bombsLaunched;
    public long bombsLanded;
    public double maxBombEnergy;
    public double maxBombDistance;
    public long lightning;
    public long geysers;
    public long lavaWaterEntries;
    public long terrainRequests;

    public void observe(EngineEvent event) {
        eventCounts.merge(event.getClass().getSimpleName(), 1L, Long::sum);
        double t = event.time();
        switch (event) {
            case EruptionStarted e -> {
                eruptions++;
                Set<String> styles = new LinkedHashSet<>();
                String style = currentStyle.get(e.volcanoId());
                if (style != null) styles.add(style);
                open.put(e.volcanoId(), new Open(t, e.cause().toString(), styles));
                if (Double.isNaN(firstEruptionSeconds)) firstEruptionSeconds = t;
                milestones.add(new Milestone(t, String.format("Eruption started (%s, overpressure %.1f MPa)",
                        e.cause(), e.overpressureMPa())));
            }
            case EruptionEnded e -> {
                milestones.add(new Milestone(t, String.format(
                        "Eruption ended: %.3g m3 DRE over %.0f s (%s)", e.eruptedVolume(), e.durationSeconds(), e.cause())));
                Open o = open.remove(e.volcanoId());
                double start = o != null ? o.start() : t - e.durationSeconds();
                eruptionRecords.add(new Eruption(e.volcanoId(), start, t, e.eruptedVolume(),
                        o != null ? o.cause() : "?", o != null ? List.copyOf(o.styles()) : List.of()));
            }
            case ChamberSample e -> {
                if (e.eruptionRate() > peakEruptionRate) {
                    peakEruptionRate = e.eruptionRate();
                    peakEruptionRateSeconds = t;
                }
            }
            case AlertLevelChanged e -> milestones.add(new Milestone(t, "Alert level " + e.previous() + " -> " + e.current()));
            case EruptionStyleEstimated e -> {
                milestones.add(new Milestone(t, (e.forecast() ? "Forecast style " : "Estimated style ") + e.previous()
                        + " -> " + e.current() + " (VEI " + e.vei() + ")"));
                if (e.forecast()) break; // only what eruptions actually did counts as their style
                maxVei = Math.max(maxVei, e.vei());
                String style = e.current().toString();
                currentStyle.put(e.volcanoId(), style);
                lastEstimatedStyle = style;
                firstStyleSeconds.putIfAbsent(style, t);
                Open o = open.get(e.volcanoId());
                if (o != null) o.styles().add(style);
            }
            case SeismicEvent e -> {
                seismicCounts.merge(e.type(), 1L, Long::sum);
                if (e.type() == SeismicEventType.EXPLOSION) explosionTimes.add(t);
                maxMagnitude = Math.max(maxMagnitude, e.magnitude());
            }
            case PlumeColumn e -> {
                maxPlumeTopY = Math.max(maxPlumeTopY, e.topY());
                maxPlumeMassRate = Math.max(maxPlumeMassRate, e.massEruptionRate());
            }
            case LavaFlowFront e -> {
                maxFlowLengthM = Math.max(maxFlowLengthM, e.lengthM());
                if (Double.isNaN(firstLavaFlowSeconds)) firstLavaFlowSeconds = t;
                if (!Double.isNaN(phreatomagmaticEndSeconds) && Double.isNaN(lavaAfterPhreatomagmaticSeconds)) {
                    lavaAfterPhreatomagmaticSeconds = t;
                }
            }
            case PhreatomagmaticChanged e -> {
                if (e.active() && Double.isNaN(phreatomagmaticStartSeconds)) {
                    phreatomagmaticStartSeconds = t;
                    milestones.add(new Milestone(t, String.format("Phreatomagmatic (Surtseyan) phase began at %.0f m water depth",
                            e.waterDepthM())));
                } else if (!e.active() && !Double.isNaN(phreatomagmaticStartSeconds)
                        && Double.isNaN(phreatomagmaticEndSeconds)) {
                    phreatomagmaticEndSeconds = t;
                    milestones.add(new Milestone(t, "Phreatomagmatic phase ended (vent sealed from the water)"));
                }
            }
            case BombLaunched e -> bombsLaunched++;
            case BombLanded e -> {
                bombsLanded++;
                maxBombEnergy = Math.max(maxBombEnergy, e.energyJoules());
                maxBombDistance = Math.max(maxBombDistance, Math.hypot(e.position().x(), e.position().z()));
            }
            case VolcanicLightning e -> lightning++;
            case GeyserFormed e -> {
                geysers++;
                if (geysers == 1) milestones.add(new Milestone(t, "First geyser formed at " + e.potentSulfur()));
            }
            case HydrothermalFeatureFormed e -> {
                long n = featuresFormed.merge(e.feature(), 1L, Long::sum);
                if (n == 1) milestones.add(new Milestone(t, "First " + e.feature() + " at " + e.pos()));
            }
            case LavaOceanEntry e -> {
                lavaWaterEntries++;
                if (lavaWaterEntries == 1) milestones.add(new Milestone(t, "Lava reached water at " + e.pos()));
            }
            case TerrainNeeded e -> terrainRequests += e.chunks().size();
            default -> { }
        }
    }

    /**
     * Closes the books at the end of a run: eruptions still going are recorded with the volume their
     * chamber has erupted so far.
     */
    public void finish(List<VolcanoSystem> volcanoes) {
        for (VolcanoSystem v : volcanoes) {
            Open o = open.remove(v.volcanoId());
            if (o == null) continue;
            eruptionRecords.add(new Eruption(v.volcanoId(), o.start(), Double.NaN, v.chamber().eruptedVolume(),
                    o.cause(), List.copyOf(o.styles())));
        }
        open.clear();
    }

    /** Total DRE volume erupted by every eruption seen in the run (m³). */
    public double totalEruptedVolume() {
        double total = 0;
        for (Eruption e : eruptionRecords) total += e.volumeM3();
        return total;
    }

    /** Also tracks the peak rate from samples (ChamberSample may be disabled). */
    public void observe(Sample sample) {
        double rate = sample.get("eruption_rate_m3s");
        if (rate > peakEruptionRate) {
            peakEruptionRate = rate;
            peakEruptionRateSeconds = sample.timeSeconds();
        }
    }
}
