package me.alex4386.typhon.simulator.run;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import me.alex4386.typhon.engine.alert.AlertEvents.AlertLevelChanged;
import me.alex4386.typhon.engine.alert.AlertEvents.EruptionStyleSuggested;
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
                if (Double.isNaN(firstEruptionSeconds)) firstEruptionSeconds = t;
                milestones.add(new Milestone(t, String.format("Eruption started (%s, overpressure %.1f MPa)",
                        e.cause(), e.overpressureMPa())));
            }
            case EruptionEnded e -> milestones.add(new Milestone(t, String.format(
                    "Eruption ended: %.3g m3 DRE over %.0f s (%s)", e.eruptedVolume(), e.durationSeconds(), e.cause())));
            case ChamberSample e -> {
                if (e.eruptionRate() > peakEruptionRate) {
                    peakEruptionRate = e.eruptionRate();
                    peakEruptionRateSeconds = t;
                }
            }
            case AlertLevelChanged e -> milestones.add(new Milestone(t, "Alert level " + e.previous() + " -> " + e.current()));
            case EruptionStyleSuggested e -> milestones.add(new Milestone(t, "Eruption style " + e.previous() + " -> " + e.current()));
            case SeismicEvent e -> {
                seismicCounts.merge(e.type(), 1L, Long::sum);
                maxMagnitude = Math.max(maxMagnitude, e.magnitude());
            }
            case PlumeColumn e -> {
                maxPlumeTopY = Math.max(maxPlumeTopY, e.topY());
                maxPlumeMassRate = Math.max(maxPlumeMassRate, e.massEruptionRate());
            }
            case LavaFlowFront e -> maxFlowLengthM = Math.max(maxFlowLengthM, e.lengthM());
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

    /** Also tracks the peak rate from samples (ChamberSample may be disabled). */
    public void observe(Sample sample) {
        double rate = sample.get("eruption_rate_m3s");
        if (rate > peakEruptionRate) {
            peakEruptionRate = rate;
            peakEruptionRateSeconds = sample.timeSeconds();
        }
    }
}
