package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.magma.MagmaCommands.InjectRecharge;
import me.alex4386.typhon.engine.magma.MagmaCommands.SetSupplyMagma;
import me.alex4386.typhon.engine.magma.MagmaCommands.SetSupplyRate;
import me.alex4386.typhon.engine.magma.MagmaCommands.StartEruption;
import me.alex4386.typhon.engine.magma.MagmaCommands.StopEruption;
import me.alex4386.typhon.engine.magma.MagmaEvents.Cause;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionEnded;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class MagmaChamberTest {
    private static final Point3 CENTER = new Point3(0, -4000, 0);

    /** A small, weakly supplied chamber (0.05 km³) so mechanics tests cycle quickly. */
    private static MagmaChamberConfig.Builder steady() {
        return MagmaChamberConfig.builder("v", CENTER).volume(5e7).supplyRate(0.01).supplyVariability(0)
                .stepPeriodSeconds(1); // these tests count 50-ms ticks
    }

    /** Steps until {@code seconds} have passed (adaptive engines take long quiet steps). */
    private static <T extends EngineEvent> List<T> runFor(Engine engine, double seconds, Class<T> type) {
        List<T> found = new ArrayList<>();
        double end = engine.time() + seconds;
        while (engine.time() < end) {
            for (EngineEvent event : engine.step().events()) {
                if (type.isInstance(event)) found.add(type.cast(event));
            }
        }
        return found;
    }

    private static <T extends EngineEvent> List<T> run(Engine engine, int ticks, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            for (EngineEvent event : engine.step().events()) {
                if (type.isInstance(event)) found.add(type.cast(event));
            }
        }
        return found;
    }

    @Test
    void steadyRechargeRaisesOverpressureElastically() {
        MagmaChamberConfig config = steady().build();
        MagmaChamber chamber = new MagmaChamber(config);
        assertEquals(0, chamber.exsolvedWaterWt(), "default magma is undersaturated");
        Engine engine = Engine.builder(0).adaptive(3600).add(chamber).build();

        runFor(engine, 3e6, EngineEvent.class); // about a month
        double seconds = engine.time();

        double expected = config.supplyRate() * seconds / (config.volume() * new MagmaChamber(config).bubbleFreeCompressibility());
        assertEquals(expected, chamber.overpressureMPa(), expected * 0.01);
        assertEquals(config.supplyRate() / (config.volume() * new MagmaChamber(config).bubbleFreeCompressibility()),
                chamber.overpressureRateMPaPerSecond(), 1e-12);
        assertFalse(chamber.erupting());
    }

    /** A chamber whose vent has a fully molten conduit (a persistently active vent). */
    private static MagmaChamberConfig.Builder openVent() {
        return steady().conduit(ConduitConfig.DEFAULT.withInitialOpenness(1));
    }

    @Test
    void withoutAConduitOnlyADikeStartsAnEruptionWhichDrainsUntilItsConduitFreezes() {
        MagmaChamberConfig config = steady().initialOverpressureMPa(14.5).conduitRadius(3).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).adaptive(3600).add(chamber).build();

        // no conduit: past its roof strength the chamber does not erupt by itself (only a dike gets magma up)
        assertTrue(runFor(engine, 30 * 86_400, EruptionStarted.class).isEmpty(), "no way up without a conduit or dike");
        assertTrue(chamber.overpressureMPa() >= config.tensileStrengthMPa(), "pressurised past the roof strength");
        assertEquals(0, chamber.conduitOpenness());

        // a dike reaching the surface starts the eruption at the pressure the chamber has
        chamber.requestFlankEruption();
        List<EngineEvent> events = new ArrayList<>();
        double endOverpressure = Double.NaN;
        for (double end = engine.time() + 30 * 86_400; engine.time() < end; ) {
            List<EngineEvent> step = engine.step().events();
            events.addAll(step);
            if (Double.isNaN(endOverpressure) && step.stream().anyMatch(EruptionEnded.class::isInstance)) {
                endOverpressure = chamber.overpressureMPa();
            }
        }
        EruptionStarted started = events.stream().filter(EruptionStarted.class::isInstance)
                .map(EruptionStarted.class::cast).findFirst().orElse(null);
        EruptionEnded ended = events.stream().filter(EruptionEnded.class::isInstance)
                .map(EruptionEnded.class::cast).findFirst().orElse(null);

        assertNotNull(started);
        assertEquals(Cause.DIKE, started.cause());
        assertTrue(started.overpressureMPa() >= config.tensileStrengthMPa());
        assertNotNull(ended, "eruption should end once the waning flow lets its conduit freeze");
        assertEquals(Cause.AUTOMATIC, ended.cause());
        assertTrue(ended.time() > started.time());

        // Mass balance: erupted volume ≈ elastic storage released (supply is negligible while erupting).
        double released = config.volume() * new MagmaChamber(config).bubbleFreeCompressibility()
                * (started.overpressureMPa() - endOverpressure);
        assertEquals(released, ended.eruptedVolume(), released * 0.05);
    }

    @Test
    void eruptionRateFollowsTheConduitFlowAndWanes() {
        MagmaChamberConfig config = steady().initialOverpressureMPa(15).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        chamber.requestFlankEruption(); // a dike has reached the surface

        run(engine, 40, EngineEvent.class); // starts on first step, flows on the next
        assertTrue(chamber.erupting());
        double flow = chamber.conduitFlow().dreRateM3PerS();
        assertEquals(flow, chamber.eruptionRate(), flow * 0.05, "the chamber drains at the conduit's steady rate");

        double early = chamber.eruptionRate();
        run(engine, 20 * 1800, EngineEvent.class);
        assertTrue(chamber.eruptionRate() < early, "eruption rate wanes as the chamber depressurises");
    }

    @Test
    void chamberMagmaCanBeReplacedInPlace() {
        MagmaChamber chamber = new MagmaChamber(steady().build());
        Engine engine = Engine.builder(0).add(chamber).build();
        run(engine, 5, EngineEvent.class);
        double co2 = chamber.bulkCo2Wt();
        engine.submit(new MagmaCommands.SetChamberMagma("v", 900.0, 66.0, 4.0, null));
        engine.step();
        assertEquals(900, chamber.temperatureC(), 5, "the chamber magma takes the new temperature");
        assertEquals(66, chamber.bulkSilicaWt(), 0.5);
        assertEquals(4.0, chamber.bulkWaterWt(), 0.05);
        assertEquals(co2, chamber.bulkCo2Wt(), 1e-3, "an unset field keeps its value");
        assertTrue(chamber.crystalFraction() > 0.1, "cool dacite is crystal-rich");
        assertThrows(IllegalArgumentException.class, () -> new MagmaCommands.SetChamberMagma("v", null, 90.0, null, null));
    }

    @Test
    void forcedStartAndStopOverrideThePhysics() {
        // without a conduit there is nothing to force magma through (the override is a forced dike)
        MagmaChamber sealedChamber = new MagmaChamber(steady().build());
        Engine sealedEngine = Engine.builder(0).add(sealedChamber).build();
        sealedEngine.submit(new StartEruption("v"));
        assertTrue(run(sealedEngine, 40, EruptionStarted.class).isEmpty(), "no conduit, no forced start");

        MagmaChamberConfig config = openVent().build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        run(engine, 20, EngineEvent.class);

        engine.submit(new StartEruption("v"));
        List<EruptionStarted> starts = run(engine, 40, EruptionStarted.class);
        assertEquals(1, starts.size());
        assertEquals(Cause.FORCED, starts.get(0).cause());
        assertTrue(chamber.erupting());
        assertTrue(chamber.eruptionRate() > 0);

        double atStop = chamber.overpressureMPa();
        engine.submit(new StopEruption("v"));
        List<EruptionEnded> ends = run(engine, 20, EruptionEnded.class);
        assertEquals(1, ends.size());
        assertEquals(Cause.FORCED, ends.get(0).cause());
        assertFalse(chamber.erupting());
        assertEquals(0, chamber.eruptionRate());
        assertEquals(0, chamber.conduitOpenness(), "stopped by hand: the conduit is plugged");
        assertTrue(chamber.overpressureMPa() >= atStop, "and the chamber keeps its pressure (the supply still adds)");

        // Not re-erupting right away.
        assertTrue(run(engine, 20 * 60, EruptionStarted.class).isEmpty());
    }

    @Test
    void rechargePulseRaisesPressureAndMixesInHotMagma() {
        MagmaChamberConfig config = steady().supplyRate(0).initialTemperatureC(1000).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();

        double volume = 5e3;
        engine.submit(new InjectRecharge("v", volume, 1250, 48, 1.0));
        engine.step();

        double stiffness = config.volume() * new MagmaChamber(config).bubbleFreeCompressibility();
        assertEquals(volume / stiffness, chamber.overpressureMPa(), 1e-6);
        assertTrue(chamber.temperatureC() > 1000);
        assertTrue(chamber.bulkSilicaWt() < config.initialSilicaWt());
    }

    @Test
    void crystalRichBatchesBringLessHeat() {
        MagmaChamberConfig config = steady().supplyRate(0).initialTemperatureC(1000).build();
        MagmaChamber melt = new MagmaChamber(config);
        MagmaChamber mush = new MagmaChamber(config);
        Engine a = Engine.builder(0).add(melt).build();
        Engine b = Engine.builder(0).add(mush).build();
        a.submit(new InjectRecharge("v", 5e6, 1150, 50, 1.0, 0.2, 0.0));
        b.submit(new InjectRecharge("v", 5e6, 1150, 50, 1.0, 0.2, 0.5));
        a.step();
        b.step();
        assertTrue(melt.temperatureC() > mush.temperatureC(),
                "crystals already released their latent heat: " + melt.temperatureC() + " vs " + mush.temperatureC());
        assertTrue(mush.temperatureC() > 1000, "but a hotter batch still heats the chamber");
        assertTrue(melt.bulkCo2Wt() > mush.bulkCo2Wt(), "CO₂ comes with the melt fraction only");
        assertEquals(melt.overpressureMPa(), mush.overpressureMPa(), 1e-12, "volume sets the pressure");
    }

    @Test
    void mixingConservesEnthalpy() {
        double before = MagmaChamber.enthalpy(1000, MagmaChamber.crystalFraction(1000, 52));
        double batch = MagmaChamber.enthalpy(1200, 0.1);
        MagmaChamberConfig config = steady().supplyRate(0).initialTemperatureC(1000).initialSilicaWt(52).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        double volume = 1e7;
        engine.submit(new InjectRecharge("v", volume, 1200, 52, 1.0, 0.0, 0.1));
        engine.step();
        double f = volume / (config.volume() + volume);
        double expected = before + f * (batch - before);
        double after = MagmaChamber.enthalpy(chamber.temperatureC(), chamber.crystalFraction());
        assertEquals(expected, after, 1e-6 * expected);
    }

    @Test
    void supplyMagmaIsSetAtRunTimeAndSaved() {
        MagmaChamberConfig config = steady().supplyRate(1).initialTemperatureC(1050).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        engine.submit(new SetSupplyMagma("v", 2.0, 1250.0, 47.0, 0.5, 0.4, 0.05, null));
        engine.step();
        MagmaChamber.SupplyMagma supply = chamber.supply();
        assertEquals(new MagmaChamber.SupplyMagma(2.0, 1250, 47, 0.5, 0.4, 0.05, config.supplyVariability()), supply);
        run(engine, 20 * 600, EngineEvent.class);
        assertTrue(chamber.temperatureC() > 1050, "the hot supply heats the chamber");
        assertTrue(chamber.bulkSilicaWt() < config.initialSilicaWt(), "and makes it more mafic");
        assertTrue(chamber.bulkCo2Wt() > 0);

        InMemorySaveStore saved = Saves.save(engine);
        MagmaChamber restored = new MagmaChamber(config);
        Engine.builder(0).add(restored).restore(saved).build();
        assertEquals(supply, restored.supply(), "the runtime supply is state");
        restored.resetSupplyFromConfig();
        assertEquals(config.supplyRate(), restored.supply().rate());
        assertEquals(config.rechargeTemperatureC(), restored.supply().temperatureC());
    }

    @Test
    void magmaCommandsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new SetSupplyMagma("v", -1.0, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SetSupplyMagma("v", null, null, null, null, null, 0.95, null));
        assertThrows(IllegalArgumentException.class, () -> new InjectRecharge("v", 1, 1200, 90, 1));
        assertThrows(IllegalArgumentException.class, () -> new InjectRecharge("v", 0, 1200, 50, 1));
    }

    @Test
    void supplyRateCommandTakesEffect() {
        MagmaChamber chamber = new MagmaChamber(steady().build());
        Engine engine = Engine.builder(0).add(chamber).build();
        engine.submit(new SetSupplyRate("v", 0));
        run(engine, 200, EngineEvent.class);
        assertEquals(0, chamber.supplyRate());
        assertEquals(0, chamber.overpressureRateMPaPerSecond(), 1e-12);
    }

    @Test
    void coolingChamberCrystallisesAndEvolvesTowardsSilicicWetMelt() {
        MagmaChamberConfig config = steady()
                .supplyRate(0)
                .initialTemperatureC(1150)
                .coolingTimescale(5e7)
                .build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).adaptive(3600).add(chamber).build();

        double t0 = chamber.temperatureC();
        double phi0 = chamber.crystalFraction();
        double si0 = chamber.silicaWt();
        double w0 = chamber.waterWt();
        double eta0 = chamber.viscosityLog10();

        runFor(engine, 1.5e7, EngineEvent.class); // about half a year

        assertTrue(chamber.temperatureC() < t0 - 100, "cools");
        assertTrue(chamber.crystalFraction() > phi0, "crystallises");
        assertTrue(chamber.silicaWt() > si0 + 1, "melt becomes more silicic");
        assertTrue(chamber.waterWt() > w0, "water concentrates in the residual melt");
        assertTrue(chamber.viscosityLog10() > eta0, "becomes more viscous");
        assertEquals(config.initialSilicaWt(), chamber.bulkSilicaWt(), 1e-9, "bulk composition is conserved");
    }

    @Test
    void saturatedMagmaExsolvesGasThatBuffersPressure() {
        MagmaChamberConfig dry = steady().initialWaterWt(1.0).build();
        MagmaChamberConfig wet = steady().initialWaterWt(6.0).build();
        MagmaChamber dryChamber = new MagmaChamber(dry);
        MagmaChamber wetChamber = new MagmaChamber(wet);

        assertEquals(0, dryChamber.gasVolumeFraction());
        assertTrue(wetChamber.exsolvedWaterWt() > 0);
        assertTrue(wetChamber.gasVolumeFraction() > 0);
        assertEquals(wetChamber.waterSolubilityWt(), wetChamber.waterWt(), 1e-12, "melt is saturated");
        assertTrue(wetChamber.effectiveCompressibility() > dryChamber.effectiveCompressibility());

        Engine a = Engine.builder(0).add(dryChamber).build();
        Engine b = Engine.builder(0).add(wetChamber).build();
        run(a, 20 * 60, EngineEvent.class);
        run(b, 20 * 60, EngineEvent.class);
        assertTrue(wetChamber.overpressureMPa() < dryChamber.overpressureMPa(), "bubbly magma is more compressible");
    }

    @Test
    void wetMagmaFragmentsAndErupsFasterThanItsViscosityAllows() {
        MagmaChamber wet = new MagmaChamber(steady().initialSilicaWt(68).initialTemperatureC(880).initialWaterWt(4.5).build());
        assertTrue(wet.fragmented(), "failure would start a fragmenting flow");
        double coherentPoiseuille = Math.PI * Math.pow(1.5, 4) * wet.config().tensileStrengthMPa() * 1e6
                / (8 * Math.pow(10, wet.viscosityLog10()) * 4000);
        assertTrue(wet.forecastFlow().dreRateM3PerS() > 100 * coherentPoiseuille,
                "fragmented flow is far faster than coherent magma of that viscosity could rise");
        MagmaChamber degassed = new MagmaChamber(steady().initialSilicaWt(62).initialTemperatureC(950).initialWaterWt(0.3)
                .tensileStrengthMPa(5).build());
        assertFalse(degassed.fragmented(), "degassed, viscous magma would rise coherently: " + degassed.forecastFlow());
    }

    @Test
    void persistentEruptionWhenSupplyOutpacesTheConduit() {
        MagmaChamberConfig config = steady()
                .initialOverpressureMPa(15)
                .supplyRate(50)
                .build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        chamber.requestFlankEruption();
        // The degassing (more viscous) conduit relaxes slowly (τ = Vβ/c ≈ 7 h here); run several τ.
        run(engine, 20 * 3600 * 48, EngineEvent.class);
        assertTrue(chamber.erupting());
        assertEquals(50, chamber.eruptionRate(), 5, "steady state: output matches supply");
    }

    @Test
    void routesCommandsToTheRightChamber() {
        ConduitConfig open = ConduitConfig.DEFAULT.withInitialOpenness(1);
        MagmaChamber a = new MagmaChamber(MagmaChamberConfig.builder("a", CENTER).supplyVariability(0).conduit(open).build());
        MagmaChamber b = new MagmaChamber(MagmaChamberConfig.builder("b", new Point3(500, -4000, 0)).supplyVariability(0)
                .conduit(open).build());
        Engine engine = Engine.builder(0).add(a).add(b).build();

        engine.submit(new StartEruption("b"));
        run(engine, 40, EngineEvent.class);
        assertFalse(a.erupting());
        assertTrue(b.erupting());

        engine.submit(new StartEruption("nope")); // no such volcano: ignored by every chamber
        run(engine, 40, EngineEvent.class);
        assertFalse(a.erupting());

        MagmaChamber duplicate = new MagmaChamber(MagmaChamberConfig.builder("a", CENTER).build());
        assertThrows(IllegalArgumentException.class,
                () -> Engine.builder(0).add(new MagmaChamber(MagmaChamberConfig.builder("a", CENTER).build())).add(duplicate).build());
    }

    @Test
    void sameSeedSameHistoryAndSaveRestoreIsBitForBit() {
        MagmaChamberConfig config = MagmaChamberConfig.builder("v", CENTER).initialOverpressureMPa(13).build();

        Engine reference = Engine.builder(9).add(new MagmaChamber(config)).build();
        List<EngineFrame> expected = new ArrayList<>();
        for (int i = 0; i < 20 * 900; i++) expected.add(reference.step());

        Engine twin = Engine.builder(9).add(new MagmaChamber(config)).build();
        for (int i = 0; i < 20 * 900; i++) assertEquals(expected.get(i), twin.step());

        Engine before = Engine.builder(9).add(new MagmaChamber(config)).build();
        for (int i = 0; i < 20 * 400; i++) before.step();
        before.submit(new InjectRecharge("v", 100, 1200, 49, 2));
        InMemorySaveStore saved = Saves.save(before);
        // the still-queued command is saved with the engine and re-queued on restore
        Engine resumed = Engine.builder(9).add(new MagmaChamber(config))
                .restore(saved).build();
        for (int i = 0; i < 20 * 500; i++) assertEquals(before.step(), resumed.step());
    }
}
