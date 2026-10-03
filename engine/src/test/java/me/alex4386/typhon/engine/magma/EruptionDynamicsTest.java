package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionEnded;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptiveRegimeChanged;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.volcano.EruptiveRegime;
import org.junit.jupiter.api.Test;

/** Conduit-controlled eruption dynamics: explosive → effusive transitions and explosion cycles. */
class EruptionDynamicsTest {
    private static final BlockPos CENTER = new BlockPos(0, -40, 0);

    /** St. Helens-like wet dacite under a sealed conduit, at failure. Eruptions run ×20 faster. */
    static MagmaChamberConfig dacite() {
        return MagmaChamberConfig.builder("v", CENTER)
                .volume(5e8).lithostaticDepth(7500).conduitRadius(15)
                .initialSilicaWt(64).rechargeSilicaWt(62)
                .initialWaterWt(4.6).rechargeWaterWt(4.6)
                .initialTemperatureC(920).rechargeTemperatureC(950)
                .initialOverpressureMPa(15.1)
                .maxEruptionRate(8000)
                .supplyRate(1).supplyVariability(0)
                .eruptiveTimeScale(20)
                .build();
    }

    /** Stromboli-like open-vent basalt near its persistent steady state. */
    static MagmaChamberConfig openVentBasalt() {
        return MagmaChamberConfig.builder("v", CENTER)
                .volume(5e7).lithostaticDepth(3000).conduitRadius(0.8)
                .tensileStrengthMPa(8).eruptionEndOverpressureMPa(0.5)
                .supplyRate(0.4).supplyVariability(0)
                .initialSilicaWt(50).rechargeSilicaWt(50)
                .initialWaterWt(2.7).rechargeWaterWt(2.7)
                .initialTemperatureC(1140).rechargeTemperatureC(1150)
                .initialOverpressureMPa(1.5)
                .conduit(ConduitConfig.DEFAULT.withInitialOpenness(1).withReopenOverpressureMPa(1.5))
                .build();
    }

    record Run(List<EngineEvent> events, List<ConduitBurst> bursts) {}

    static Run run(Engine engine, MagmaChamber chamber, int ticks) {
        List<EngineEvent> events = new ArrayList<>();
        List<ConduitBurst> bursts = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            events.addAll(engine.tick().events());
            bursts.addAll(chamber.drainBursts());
        }
        return new Run(events, bursts);
    }

    static <T> List<T> of(List<EngineEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void plinianOnsetRelaxesToDegassedDomeGrowth() {
        MagmaChamber chamber = new MagmaChamber(dacite());
        Engine engine = Engine.builder(0).add(chamber).build();
        Run run = run(engine, chamber, 20 * 60 * 20);

        List<EruptiveRegimeChanged> regimes = of(run.events(), EruptiveRegimeChanged.class);
        assertEquals(EruptiveRegime.EXPLOSIVE, regimes.get(0).current(), "a sealed conduit failing at high pressure fragments");
        assertTrue(regimes.get(0).ventWaterWt() > 4, "the Plinian column carries its water");

        List<EruptionStarted> starts = of(run.events(), EruptionStarted.class);
        assertTrue(starts.size() >= 2, "the chamber recharges and erupts again");
        EruptionStarted second = starts.get(1);
        assertTrue(second.overpressureMPa() < dacite().tensileStrengthMPa() / 2,
                "an open conduit re-opens far below the tensile strength: " + second.overpressureMPa());
        assertEquals(EruptiveRegime.DOME, chamber.eruptiveRegime(), "slow ascent outgasses into a dome");
        assertTrue(chamber.ventWaterWt() < 0.5, "dome lava is degassed: " + chamber.ventWaterWt());
        assertTrue(chamber.waterWt() > 4, "while the chamber itself stays water-rich");
        assertFalse(chamber.fragmented());
    }

    @Test
    void domePlugFailsInVulcanianCycles() {
        MagmaChamberConfig config = dacite();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        Run run = run(engine, chamber, 20 * 3600);

        List<ConduitBurst> vulcanian = run.bursts().stream().filter(b -> b.kind() == ConduitBurst.Kind.VULCANIAN).toList();
        assertTrue(vulcanian.size() >= 2, "repeated plug failures: " + vulcanian.size());
        assertTrue(run.bursts().stream().noneMatch(b -> b.kind() == ConduitBurst.Kind.STROMBOLIAN),
                "gas slugs cannot form in viscous dacite");
        double resealTicks = config.conduit().plugResealSeconds() / config.eruptiveTimeScale() * 20;
        for (int i = 1; i < vulcanian.size(); i++) {
            assertTrue(vulcanian.get(i).tick() - vulcanian.get(i - 1).tick() > resealTicks, "a new plug must seal first");
        }
        for (ConduitBurst b : vulcanian) {
            assertTrue(b.overpressureMPa() >= config.conduit().plugStrengthMPa());
            assertEquals(config.conduit().vulcanianGasMassFraction(), b.gasMassFraction(), 1e-9);
        }
    }

    @Test
    void openVentBasaltBurstsQuasiPeriodically() {
        MagmaChamberConfig config = openVentBasalt();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        Run run = run(engine, chamber, 20 * 3600 * 2);

        assertTrue(chamber.erupting(), "supply balances outflow: persistent activity");
        assertEquals(EruptiveRegime.OPEN_VENT, chamber.eruptiveRegime());
        List<Long> ticks = run.bursts().stream().map(ConduitBurst::tick).toList();
        assertTrue(ticks.size() >= 15, "Strombolian explosions: " + ticks.size());
        assertTrue(run.bursts().stream().allMatch(b -> b.kind() == ConduitBurst.Kind.STROMBOLIAN));

        double[] intervals = new double[ticks.size() - 1];
        double mean = 0;
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = (ticks.get(i + 1) - ticks.get(i)) / 20.0;
            mean += intervals[i] / intervals.length;
        }
        double var = 0;
        for (double v : intervals) var += (v - mean) * (v - mean) / intervals.length;
        double cv = Math.sqrt(var) / mean;
        assertTrue(cv < 0.8, "slug bursts are more regular than a Poisson process (CV 1): " + cv);

        // Mean interval ≈ slug mass / (slug share × gas flux).
        double rate = chamber.eruptionRate();
        double gasFlux = rate * 2500 * (chamber.waterWt() - config.conduit().degassedWaterWt()) / 100;
        double expected = config.conduit().slugGasMassKg() / (config.conduit().slugGasFraction() * gasFlux);
        assertEquals(expected, mean, expected * 0.5, "mean interval");
        assertTrue(mean > 60 && mean < 900, "Stromboli-like: a few to ~15 explosions per hour, was " + mean + " s");
    }

    @Test
    void openConduitSealsDuringLongRepose() {
        MagmaChamberConfig config = dacite().toBuilder().supplyRate(0).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        boolean ended = false;
        for (int i = 0; i < 20 * 600 && !ended; i++) {
            ended = !of(engine.tick().events(), EruptionEnded.class).isEmpty();
        }
        assertTrue(ended);
        assertEquals(config.reopenOverpressureMPa(), chamber.failureOverpressureMPa(), 1e-9, "freshly open conduit");

        run(engine, chamber, 20 * 3600 * 2); // years of repose at ×5000
        assertTrue(chamber.conduitOpenness() < 0.5);
        assertTrue(chamber.failureOverpressureMPa() > 0.5 * config.tensileStrengthMPa(), "sealed again");
        assertTrue(chamber.fragmented(), "the next failure of a sealed conduit would be explosive again");
    }

    @Test
    void conduitStateResumesBitForBit() {
        MagmaChamberConfig config = dacite();
        int before = 20 * 60 * 25;
        int after = 20 * 60 * 20;

        MagmaChamber reference = new MagmaChamber(config);
        Engine refEngine = Engine.builder(3).add(reference).build();
        Run all = run(refEngine, reference, before + after);

        MagmaChamber first = new MagmaChamber(config);
        Engine engine = Engine.builder(3).add(first).build();
        run(engine, first, before);
        assertTrue(first.plugPressureMPa() > 0, "save with gas trapped under the plug");
        String saved = engine.saveState().toString();

        MagmaChamber second = new MagmaChamber(config);
        Engine resumed = Engine.builder(3).add(second).restore(JsonParser.parseString(saved).getAsJsonObject()).build();
        Run rest = run(resumed, second, after);

        List<ConduitBurst> expected = all.bursts().stream().filter(b -> b.tick() >= before).toList();
        assertEquals(expected, rest.bursts());
        assertEquals(all.events().subList(all.events().size() - rest.events().size(), all.events().size()), rest.events());
        assertEquals(reference.overpressureMPa(), second.overpressureMPa());
        assertEquals(reference.plugPressureMPa(), second.plugPressureMPa());
    }
}
