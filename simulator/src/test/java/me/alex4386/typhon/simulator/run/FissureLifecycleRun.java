package me.alex4386.typhon.simulator.run;

import java.util.Locale;
import me.alex4386.typhon.engine.dike.DikeCommands;
import me.alex4386.typhon.engine.dike.DikeEvents;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.magma.MagmaEvents;
import me.alex4386.typhon.engine.volcano.VentCommands;
import me.alex4386.typhon.engine.volcano.VentStatus;
import me.alex4386.typhon.engine.volcano.VentEvents;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Timeline of a forced flank fissure on Kīlauea at real scale: the fissure opens, its flow localises,
 * wanes and freezes. Opt-in ({@code TYPHON_FISSURE_TIMELINE=1}); prints the timeline.
 */
class FissureLifecycleRun {
    @Test
    @EnabledIfEnvironmentVariable(named = "TYPHON_FISSURE_TIMELINE", matches = "1")
    void forcedDikeTimeline() {
        Preset preset = Presets.get("kilauea-real");
        long stepMs = Long.parseLong(System.getenv().getOrDefault("TYPHON_FISSURE_STEP_MS", "50"));
        Scenario scenario = preset.build(1, preset.terrain(1), Scenario.Options.DEFAULT.withBaseStepMicros(stepMs * 1000));
        String vid = scenario.volcano().volcanoId();
        double hours = Double.parseDouble(System.getenv().getOrDefault("TYPHON_FISSURE_HOURS", "6"));
        scenario.engine().submit(new DikeCommands.ForceDike(vid));
        Simulation sim = new Simulation(scenario, 600).onEvent(e -> {
            String line = switch (e) {
                case DikeEvents.FissureOpened f -> "fissure opened " + f.vent().id() + " length " + f.vent().fissureLength();
                case DikeEvents.DikeStalled s -> "dike " + s.dikeId() + " stalled " + s.reason();
                case MagmaEvents.EruptionStarted s -> "eruption started " + s.cause() + " at " + fmt(s.overpressureMPa()) + " MPa";
                case MagmaEvents.EruptionEnded s -> "eruption ended " + s.cause() + " " + fmt(s.eruptedVolume()) + " m3";
                case VentEvents.VentStateChanged v -> "vent " + v.ventId() + " " + v.previous() + " -> " + v.current()
                        + " w=" + fmt(v.feederWidthM());
                default -> null;
            };
            if (line != null) System.out.println(clock(e.time()) + "  " + line);
        });
        double printed = -1;
        double every = Double.parseDouble(System.getenv().getOrDefault("TYPHON_FISSURE_EVERY_S", "600"));
        String mode = System.getenv().getOrDefault("TYPHON_FISSURE_SCENARIO", "natural");
        double supply = scenario.volcano().chamber().supplyRate();
        boolean supplyCut = false;
        boolean restored = false;
        boolean acted = false;
        for (double t = 0; t < hours * 3600; t += every) {
            sim.run(every / 3600.0);
            var coupler = scenario.volcano().coupler();
            String fissure = coupler.allVents().stream().map(VentSite::id).filter(id -> id.contains("-dike-"))
                    .findFirst().orElse(null);
            switch (mode) {
                // A long repose: the deep supply pauses after the first eruption, and resumes once the
                // idle feeder has frozen.
                case "longRepose" -> {
                    if (!supplyCut && fissure != null && !scenario.volcano().chamber().erupting()) {
                        scenario.engine().submit(new MagmaCommands.SetSupplyRate(vid, 0));
                        supplyCut = true;
                        System.out.println(clock(scenario.engine().time()) + "  >> supply paused");
                    } else if (supplyCut && !restored && coupler.ventStatus(fissure) == VentStatus.FROZEN) {
                        scenario.engine().submit(new MagmaCommands.SetSupplyRate(vid, supply));
                        restored = true;
                        System.out.println(clock(scenario.engine().time()) + "  >> supply resumed");
                    }
                }
                case "seal", "remove" -> {
                    if (!acted && fissure != null && scenario.engine().time() >= 3600) {
                        scenario.engine().submit(mode.equals("seal") ? new VentCommands.SealVent(vid, fissure)
                                : new VentCommands.RemoveVent(vid, fissure));
                        acted = true;
                        System.out.println(clock(scenario.engine().time()) + "  >> " + mode + " " + fissure);
                    }
                }
                default -> { }
            }
            MagmaChamber chamber = scenario.volcano().chamber();
            StringBuilder sb = new StringBuilder();
            for (VentSite vent : scenario.volcano().coupler().allVents()) {
                var c = scenario.volcano().coupler();
                int[] seg = c.feederSegments(vent.id());
                sb.append(String.format(Locale.ROOT, " | %s %s Q=%s w=%s seg=%s", vent.id(), c.ventStatus(vent.id()),
                        fmt(c.ventFluxM3PerS(vent.id())), fmt(c.feederWidthM(vent.id())),
                        seg == null ? "-" : seg[0] + "/" + seg[1]));
            }
            System.out.println(clock(scenario.engine().time()) + "  P=" + fmt(chamber.overpressureMPa()) + " MPa rate="
                    + fmt(chamber.physicalEruptionRate()) + " cap=" + fmt(chamber.outletCapacity()) + sb);
            printed = t;
        }
        if (printed < 0) System.out.println("nothing ran");
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3g", v);
    }

    private static String clock(double seconds) {
        long s = (long) seconds;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
