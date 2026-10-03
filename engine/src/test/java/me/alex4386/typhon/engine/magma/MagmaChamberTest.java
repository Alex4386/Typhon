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
import me.alex4386.typhon.engine.magma.MagmaCommands.SetSupplyRate;
import me.alex4386.typhon.engine.magma.MagmaCommands.StartEruption;
import me.alex4386.typhon.engine.magma.MagmaCommands.StopEruption;
import me.alex4386.typhon.engine.magma.MagmaEvents.Cause;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionEnded;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;

class MagmaChamberTest {
    private static final BlockPos CENTER = new BlockPos(0, -40, 0);

    private static MagmaChamberConfig.Builder steady() {
        return MagmaChamberConfig.builder("v", CENTER).supplyVariability(0);
    }

    private static <T extends EngineEvent> List<T> run(Engine engine, int ticks, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            for (EngineEvent event : engine.tick().events()) {
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
        Engine engine = Engine.builder(0).add(chamber).build();

        int seconds = 600;
        run(engine, seconds * 20, EngineEvent.class);

        double expected = config.supplyRate() * config.dormantTimeScale() * seconds
                / (config.volume() * config.compressibilityPerMPa());
        assertEquals(expected, chamber.overpressureMPa(), expected * 0.01);
        assertEquals(config.supplyRate() * config.dormantTimeScale() / (config.volume() * config.compressibilityPerMPa()),
                chamber.overpressureRateMPaPerSecond(), 1e-9);
        assertFalse(chamber.erupting());
    }

    @Test
    void eruptsAtTensileStrengthAndDrainsToEndThreshold() {
        MagmaChamberConfig config = steady().initialOverpressureMPa(14.5).conduitRadius(3).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();

        List<EngineEvent> events = run(engine, 20 * 3600 * 3, EngineEvent.class);
        EruptionStarted started = events.stream().filter(EruptionStarted.class::isInstance)
                .map(EruptionStarted.class::cast).findFirst().orElse(null);
        EruptionEnded ended = events.stream().filter(EruptionEnded.class::isInstance)
                .map(EruptionEnded.class::cast).findFirst().orElse(null);

        assertNotNull(started);
        assertEquals(Cause.AUTOMATIC, started.cause());
        assertTrue(started.overpressureMPa() >= config.tensileStrengthMPa());
        assertNotNull(ended, "eruption should end once overpressure is relieved");
        assertEquals(Cause.AUTOMATIC, ended.cause());
        assertTrue(ended.tick() > started.tick());

        // Mass balance: erupted volume ≈ elastic storage released (supply is negligible while erupting).
        double released = config.volume() * config.compressibilityPerMPa()
                * (started.overpressureMPa() - config.eruptionEndOverpressureMPa());
        assertEquals(released, ended.eruptedVolume(), released * 0.05);
    }

    @Test
    void eruptionRateFollowsPoiseuilleAndWanes() {
        MagmaChamberConfig config = steady().initialOverpressureMPa(15).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();

        run(engine, 40, EngineEvent.class); // starts on first step, flows on the next
        assertTrue(chamber.erupting());
        double viscosity = Math.pow(10, chamber.viscosityLog10());
        double r = config.conduitRadius();
        double poiseuille = Math.PI * r * r * r * r * chamber.overpressureMPa() * 1e6 / (8 * viscosity * config.lithostaticDepth());
        assertEquals(poiseuille, chamber.eruptionRate(), poiseuille * 0.02);

        double early = chamber.eruptionRate();
        run(engine, 20 * 1800, EngineEvent.class);
        assertTrue(chamber.eruptionRate() < early, "eruption rate wanes as the chamber depressurises");
    }

    @Test
    void forcedStartAndStopOverrideThePhysics() {
        MagmaChamberConfig config = steady().build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        run(engine, 20, EngineEvent.class);

        engine.submit(new StartEruption("v"));
        List<EruptionStarted> starts = run(engine, 40, EruptionStarted.class);
        assertEquals(1, starts.size());
        assertEquals(Cause.FORCED, starts.get(0).cause());
        assertTrue(chamber.erupting());
        assertTrue(chamber.eruptionRate() > 0);

        engine.submit(new StopEruption("v"));
        List<EruptionEnded> ends = run(engine, 20, EruptionEnded.class);
        assertEquals(1, ends.size());
        assertEquals(Cause.FORCED, ends.get(0).cause());
        assertFalse(chamber.erupting());
        assertEquals(0, chamber.eruptionRate());
        assertTrue(chamber.overpressureMPa() <= config.eruptionEndOverpressureMPa() + 0.01);

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
        engine.tick();

        double stiffness = config.volume() * config.compressibilityPerMPa();
        assertEquals(volume / stiffness, chamber.overpressureMPa(), 1e-6);
        assertTrue(chamber.temperatureC() > 1000);
        assertTrue(chamber.bulkSilicaWt() < config.initialSilicaWt());
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
                .dormantTimeScale(1e4)
                .build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();

        double t0 = chamber.temperatureC();
        double phi0 = chamber.crystalFraction();
        double si0 = chamber.silicaWt();
        double w0 = chamber.waterWt();
        double eta0 = chamber.viscosityLog10();

        run(engine, 20 * 1500, EngineEvent.class);

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
        assertTrue(wet.fragmented());
        assertTrue(wet.conduitConductance() > Math.PI * Math.pow(1.5, 4) * 1e6
                / (8 * Math.pow(10, wet.viscosityLog10()) * 4000) * 1000);
        MagmaChamber dry = new MagmaChamber(steady().build());
        assertFalse(dry.fragmented());
    }

    @Test
    void persistentEruptionWhenSupplyOutpacesTheConduit() {
        MagmaChamberConfig config = steady()
                .initialOverpressureMPa(15)
                .supplyRate(20)
                .build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        run(engine, 20 * 3600 * 4, EngineEvent.class);
        assertTrue(chamber.erupting());
        assertEquals(20, chamber.eruptionRate(), 2, "steady state: output matches supply");
    }

    @Test
    void routesCommandsToTheRightChamber() {
        MagmaChamber a = new MagmaChamber(MagmaChamberConfig.builder("a", CENTER).supplyVariability(0).build());
        MagmaChamber b = new MagmaChamber(MagmaChamberConfig.builder("b", new BlockPos(500, -40, 0)).supplyVariability(0).build());
        Engine engine = Engine.builder(0).add(a).add(b).build();

        engine.submit(new StartEruption("b"));
        run(engine, 40, EngineEvent.class);
        assertFalse(a.erupting());
        assertTrue(b.erupting());

        engine.submit(new StartEruption("nope"));
        assertThrows(IllegalArgumentException.class, engine::tick);

        MagmaChamber duplicate = new MagmaChamber(MagmaChamberConfig.builder("a", CENTER).build());
        assertThrows(IllegalArgumentException.class,
                () -> Engine.builder(0).add(new MagmaChamber(MagmaChamberConfig.builder("a", CENTER).build())).add(duplicate).build());
    }

    @Test
    void sameSeedSameHistoryAndSaveRestoreIsBitForBit() {
        MagmaChamberConfig config = MagmaChamberConfig.builder("v", CENTER).initialOverpressureMPa(13).build();

        Engine reference = Engine.builder(9).add(new MagmaChamber(config)).build();
        List<EngineFrame> expected = new ArrayList<>();
        for (int i = 0; i < 20 * 900; i++) expected.add(reference.tick());

        Engine twin = Engine.builder(9).add(new MagmaChamber(config)).build();
        for (int i = 0; i < 20 * 900; i++) assertEquals(expected.get(i), twin.tick());

        Engine before = Engine.builder(9).add(new MagmaChamber(config)).build();
        for (int i = 0; i < 20 * 400; i++) before.tick();
        before.submit(new InjectRecharge("v", 100, 1200, 49, 2));
        String saved = before.saveState().toString();
        // the queued command is not persisted; apply it identically on both sides
        Engine resumed = Engine.builder(9).add(new MagmaChamber(config))
                .restore(JsonParser.parseString(saved).getAsJsonObject()).build();
        resumed.submit(new InjectRecharge("v", 100, 1200, 49, 2));
        for (int i = 0; i < 20 * 500; i++) assertEquals(before.tick(), resumed.tick());
    }
}
