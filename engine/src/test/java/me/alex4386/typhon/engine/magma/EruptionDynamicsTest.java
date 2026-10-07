package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionEnded;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.testing.Saves;
import org.junit.jupiter.api.Test;

/**
 * Conduit-controlled eruption dynamics that must emerge from the flow, never chosen: fragmenting
 * onset of a sealed wet chamber relaxing to coherent outgassed extrusion, plug failures over stiff
 * lava, quasi-periodic slug bursts of an open basaltic vent.
 */
class EruptionDynamicsTest {
    private static final Point3 CENTER = new Point3(0, -4000, 0);

    /** St. Helens-like wet dacite under a sealed conduit, at failure. */
    static MagmaChamberConfig dacite() {
        return MagmaChamberConfig.builder("v", CENTER)
                .volume(5e8).lithostaticDepth(7500).conduitRadius(15)
                .initialSilicaWt(64).rechargeSilicaWt(62)
                .initialWaterWt(4.6).rechargeWaterWt(4.6)
                .initialTemperatureC(920).rechargeTemperatureC(950)
                .initialOverpressureMPa(15.1)
                .supplyRate(1).supplyVariability(0)
                .build();
    }

    /** Stromboli-like: an open conduit over CO₂-rich basalt below its re-opening pressure, slow supply. */
    static MagmaChamberConfig openVentBasalt() {
        return MagmaChamberConfig.builder("v", CENTER)
                .volume(5e7).lithostaticDepth(3000).conduitRadius(0.8)
                .tensileStrengthMPa(8).eruptionEndOverpressureMPa(0.5)
                .supplyRate(0.002).supplyVariability(0)
                .initialSilicaWt(50).rechargeSilicaWt(50)
                .initialWaterWt(2.7).rechargeWaterWt(2.7)
                .initialCo2Wt(0.3).rechargeCo2Wt(0.3)
                .initialTemperatureC(1140).rechargeTemperatureC(1150)
                .initialOverpressureMPa(0)
                .conduit(ConduitConfig.DEFAULT.withInitialOpenness(1).withReopenOverpressureMPa(1.5))
                // bursts every ~10 s: a chamber step well below that keeps their times resolved
                .stepPeriodSeconds(2)
                .build();
    }

    /** Degassed, crystal-rich dacite under an open conduit, just past re-opening. */
    static MagmaChamberConfig degassedDacite() {
        return MagmaChamberConfig.builder("v", CENTER)
                .volume(5e8).lithostaticDepth(3000).conduitRadius(10)
                .initialSilicaWt(62).rechargeSilicaWt(62)
                .initialWaterWt(0.6).rechargeWaterWt(0.6)
                .initialCo2Wt(0).rechargeCo2Wt(0)
                .initialTemperatureC(900).rechargeTemperatureC(900)
                .initialOverpressureMPa(1.6).eruptionEndOverpressureMPa(-2)
                .supplyRate(0.5).supplyVariability(0)
                .conduit(ConduitConfig.DEFAULT.withInitialOpenness(1).withReopenOverpressureMPa(1.5))
                .build();
    }

    record Run(List<EngineEvent> events, List<ConduitBurst> bursts, List<ConduitSolution> flows) {}

    static Run run(Engine engine, MagmaChamber chamber, int ticks) {
        List<EngineEvent> events = new ArrayList<>();
        List<ConduitBurst> bursts = new ArrayList<>();
        List<ConduitSolution> flows = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            events.addAll(engine.step().events());
            bursts.addAll(chamber.drainBursts());
            ConduitSolution flow = chamber.conduitFlow();
            if (flow != null && (flows.isEmpty() || flows.get(flows.size() - 1) != flow)) flows.add(flow);
        }
        return new Run(events, bursts, flows);
    }

    /** Like {@link #run} for {@code seconds} of time (adaptive engines take long quiet steps). */
    static Run runFor(Engine engine, MagmaChamber chamber, double seconds) {
        List<EngineEvent> events = new ArrayList<>();
        List<ConduitBurst> bursts = new ArrayList<>();
        List<ConduitSolution> flows = new ArrayList<>();
        double end = engine.time() + seconds;
        while (engine.time() < end) {
            events.addAll(engine.step().events());
            bursts.addAll(chamber.drainBursts());
            ConduitSolution flow = chamber.conduitFlow();
            if (flow != null && (flows.isEmpty() || flows.get(flows.size() - 1) != flow)) flows.add(flow);
        }
        return new Run(events, bursts, flows);
    }

    static <T> List<T> of(List<EngineEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void sealedWetChamberFailsExplosivelyThenReopensAtLowPressure() {
        MagmaChamber chamber = new MagmaChamber(dacite());
        Engine engine = Engine.builder(0).adaptive(3600).add(chamber).build();
        chamber.requestFlankEruption(); // the first eruption: a dike breaches the surface (there is no conduit yet)
        Run run = runFor(engine, chamber, 200 * 86_400.0); // an eruption, months of recharge, the next one

        ConduitSolution onset = run.flows().get(0);
        assertTrue(onset.fragmented(), "a sealed conduit failing at high pressure fragments: " + onset);
        assertTrue(onset.fragmentationDepthM() > 100, "deep in the conduit: " + onset.fragmentationDepthM());
        assertTrue(onset.dreRateM3PerS() > 300, "a Plinian-scale discharge: " + onset.dreRateM3PerS());

        List<EruptionStarted> starts = of(run.events(), EruptionStarted.class);
        assertTrue(starts.size() >= 2, "the chamber recharges and erupts again: " + starts.size());
        assertTrue(starts.get(1).overpressureMPa() < dacite().tensileStrengthMPa() / 2,
                "an open conduit re-opens far below the tensile strength: " + starts.get(1).overpressureMPa());
        assertTrue(chamber.waterWt() > 4, "the chamber stays water-rich, so its eruptions stay explosive");
    }

    @Test
    void degassedDaciteExtrudesStiffLavaWhosePlugFails() {
        MagmaChamber chamber = new MagmaChamber(degassedDacite());
        Engine engine = Engine.builder(0).adaptive(3600).add(chamber).build();
        Run run = runFor(engine, chamber, 800 * 3600.0); // a month of extrusion

        assertTrue(chamber.erupting(), "slow extrusion keeps going");
        ConduitSolution flow = chamber.conduitFlow();
        assertFalse(flow.fragmented(), "slow ascent keeps the magma coherent: " + flow);
        assertTrue(flow.outgassedFraction() > 0.5, "it outgasses on the way up: " + flow.outgassedFraction());
        assertTrue(flow.exitMeltViscosityLog10() > 8, "stiff, degassed, crystallising lava: " + flow.exitMeltViscosityLog10());
        assertTrue(flow.dreRateM3PerS() < 10, "dome-scale extrusion: " + flow.dreRateM3PerS());
        assertTrue(chamber.ventWaterWt() < 0.5, "lava is degassed: " + chamber.ventWaterWt());

        List<ConduitBurst> plugs = run.bursts().stream().filter(b -> b.kind() == ConduitBurst.Kind.PLUG).toList();
        assertTrue(plugs.size() >= 2, "repeated plug failures: " + plugs.size());
        assertTrue(run.bursts().stream().noneMatch(b -> b.kind() == ConduitBurst.Kind.SLUG),
                "gas slugs cannot segregate in viscous dacite");
        for (ConduitBurst b : plugs) {
            assertTrue(b.overpressureMPa() > 0.5, "a plug holds megapascals: " + b.overpressureMPa());
            assertTrue(b.gasMassFraction() < 0.1, "plug failures blast out mostly rock: " + b.gasMassFraction());
        }
    }

    @Test
    void openVentBasaltBurstsQuasiPeriodically() {
        MagmaChamberConfig config = openVentBasalt();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        Run run = run(engine, chamber, 20 * 3600 * 2);

        assertFalse(chamber.erupting(), "no lava: the chamber is below its re-opening pressure");
        List<Double> times = run.bursts().stream().map(ConduitBurst::time).toList();
        assertTrue(times.size() >= 15, "chamber gas rising through the open conduit bursts as slugs: " + times.size());
        assertTrue(run.bursts().stream().allMatch(b -> b.kind() == ConduitBurst.Kind.SLUG));

        double[] intervals = new double[times.size() - 1];
        double mean = 0;
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = times.get(i + 1) - times.get(i);
            mean += intervals[i] / intervals.length;
        }
        double var = 0;
        for (double v : intervals) var += (v - mean) * (v - mean) / intervals.length;
        double cv = Math.sqrt(var) / mean;
        assertTrue(cv < 0.8, "slug bursts are more regular than a Poisson process (CV 1): " + cv);
        assertTrue(mean > 10 && mean < 3600, "Stromboli-like: one to a few hundred explosions per hour, was " + mean + " s");

        // A stiff magma column lets the same gas escape passively instead.
        MagmaChamber viscous = new MagmaChamber(config.toBuilder().initialSilicaWt(66).initialTemperatureC(950).build());
        Engine viscousEngine = Engine.builder(0).add(viscous).build();
        assertTrue(run(viscousEngine, viscous, 20 * 3600).bursts().size() < times.size() / 4);
    }

    @Test
    void conduitSolidifiesAfterAnEruptionAndThenOnlyADikeCanReopen() {
        // a basaltic feeder 1.5 m in radius solidifies in weeks (t = a²/κ·(1 + L/cΔT))
        MagmaChamberConfig config = dacite().toBuilder().supplyRate(0).conduitRadius(1.5).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).adaptive(3600).add(chamber).build();
        chamber.requestFlankEruption();
        boolean ended = false;
        for (int i = 0; i < 200_000 && !ended; i++) {
            ended = !of(engine.step().events(), EruptionEnded.class).isEmpty();
        }
        assertTrue(ended);
        assertEquals(1, chamber.conduitOpenness(), 1e-9, "the conduit is full of melt right after the eruption");
        assertEquals(config.reopenOverpressureMPa(), chamber.failureOverpressureMPa(), 1e-9);

        double freeze = chamber.conduitFreezeSeconds();
        assertTrue(freeze > 7 * 86_400 && freeze < 365 * 86_400, "weeks to months for a 1.5 m conduit: " + freeze);
        runFor(engine, chamber, freeze / 4);
        // solid rim grows as √t: a quarter of the freezing time leaves half the radius molten
        assertEquals(0.5, chamber.conduitOpenness(), 0.05);
        runFor(engine, chamber, freeze);
        assertEquals(0, chamber.conduitOpenness(), "frozen solid: no conduit");
        assertEquals(config.tensileStrengthMPa(), chamber.failureOverpressureMPa(), 1e-9, "only rock is left to break");
    }

    @Test
    void conduitStateResumesBitForBit() {
        MagmaChamberConfig config = degassedDacite();
        int before = 20 * 60 * 25;
        int after = 20 * 60 * 20;

        MagmaChamber reference = new MagmaChamber(config);
        Engine refEngine = Engine.builder(3).add(reference).build();
        Run all = run(refEngine, reference, before + after);

        MagmaChamber first = new MagmaChamber(config);
        Engine engine = Engine.builder(3).add(first).build();
        run(engine, first, before);
        assertNotNull(first.conduitFlow(), "save mid-eruption");
        InMemorySaveStore saved = Saves.save(engine);

        MagmaChamber second = new MagmaChamber(config);
        Engine resumed = Engine.builder(3).add(second).restore(saved).build();
        Run rest = run(resumed, second, after);

        List<ConduitBurst> expected = all.bursts().stream().filter(b -> b.time() >= before / 20.0).toList();
        assertTrue(first.plugPressureMPa() > 0, "saved with gas trapped beneath the plug");
        assertEquals(expected, rest.bursts());
        assertEquals(all.events().subList(all.events().size() - rest.events().size(), all.events().size()), rest.events());
        assertEquals(reference.overpressureMPa(), second.overpressureMPa());
        assertEquals(reference.plugPressureMPa(), second.plugPressureMPa());
        assertEquals(reference.conduitFlow(), second.conduitFlow());
    }
}
