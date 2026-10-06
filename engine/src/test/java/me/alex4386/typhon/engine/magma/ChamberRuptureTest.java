package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.magma.MagmaCommands.InjectRecharge;
import me.alex4386.typhon.engine.magma.MagmaCommands.SetSupplyRate;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.testing.Saves;
import org.junit.jupiter.api.Test;

/**
 * Wall rupture bounds the chamber's overpressure, and the eruption it drives is the same per unit
 * of volcano time whatever the time compression (the stromboli repro: ×500 eruptive, 0.3 m³/s).
 */
class ChamberRuptureTest {
    private static final BlockPos CENTER = new BlockPos(0, -40, 0);
    private static final double TICK = 0.05;

    private static MagmaChamberConfig.Builder stromboli() {
        return MagmaChamberConfig.builder("v", CENTER).volume(5e7).compressibilityPerMPa(2e-4)
                .lithostaticDepth(3000).conduitRadius(0.8).tensileStrengthMPa(8).eruptionEndOverpressureMPa(0.5)
                .supplyRate(0.3).supplyVariability(0);
    }

    @Test
    void injectionBeyondTheWallsGrowsTheChamberInsteadOfThePressure() {
        MagmaChamberConfig config = stromboli().supplyRate(0).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        double cap = chamber.ruptureOverpressureMPa();
        assertTrue(cap >= config.tensileStrengthMPa() && cap <= 2 * config.tensileStrengthMPa() + 1e-9);

        double injected = 1e9; // 20 chamber volumes: the live session's "add magma" spam
        engine.submit(new InjectRecharge("v", injected, 1150, 50, 1.0));
        double peak = 0;
        for (int i = 0; i < 40; i++) {
            engine.step();
            peak = Math.max(peak, chamber.overpressureMPa());
        }
        assertTrue(peak <= cap + 1e-9, "overpressure " + peak + " exceeds the rupture limit " + cap);
        double elastic = cap * config.volume() * chamber.effectiveCompressibility();
        assertEquals(injected - elastic, chamber.inelasticVolumeChangeM3(), 0.02 * injected,
                "magma the walls cannot hold elastically is stored by growing the chamber");
        assertEquals(config.volume() + chamber.inelasticVolumeChangeM3(), chamber.volumeM3(), 1);
    }

    @Test
    void sustainedSupplyAtExtremeCompressionStaysBounded() {
        // The user's sliders: supply 0.3 m³/s, ×500 eruptive, ×5000 dormant, plus a 10× supply surge.
        MagmaChamberConfig config = stromboli().eruptiveTimeScale(500).dormantTimeScale(5000).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        double peakP = 0, peakQ = 0;
        for (int i = 0; i < 20 * 3600; i++) {
            if (i == 20 * 600) engine.submit(new SetSupplyRate("v", 3.0));
            engine.step();
            peakP = Math.max(peakP, chamber.overpressureMPa());
            peakQ = Math.max(peakQ, chamber.physicalEruptionRate());
            assertTrue(Double.isFinite(chamber.overpressureMPa()));
        }
        assertTrue(peakP <= chamber.ruptureOverpressureMPa() + 1e-9, "peak overpressure " + peakP);
        assertTrue(chamber.eruptionCount() > 0, "the chamber erupts");
        assertTrue(peakQ < 1e3, "physical eruption rate stays volcanic, not runaway: " + peakQ);
    }

    /** Erupted volume and overpressure after {@code physical} seconds of eruption at a time scale. */
    private static double[] eruptAt(double scale, double physical) {
        MagmaChamberConfig config = stromboli().eruptiveTimeScale(scale).initialOverpressureMPa(7.9).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).add(chamber).build();
        double since = Double.NaN;
        while (!(since >= physical)) {
            engine.step();
            if (chamber.erupting()) since = Double.isNaN(since) ? 0 : since + TICK * scale;
            assertTrue(chamber.overpressureMPa() <= chamber.ruptureOverpressureMPa() + 1e-9);
        }
        return new double[] {chamber.eruptedVolume(), chamber.overpressureMPa(), chamber.physicalEruptionRate()};
    }

    @Test
    void eruptionConvergesAcrossTimeCompression() {
        double physical = 3 * 3600;
        double[] reference = eruptAt(1, physical);
        assertTrue(reference[2] > 0.1 && reference[2] < 100, "a Strombolian-scale effusion rate: " + reference[2]);
        for (double scale : new double[] {20, 200, 500}) {
            double[] r = eruptAt(scale, physical);
            assertEquals(reference[0], r[0], 0.05 * reference[0], "erupted volume at ×" + scale);
            assertEquals(reference[1], r[1], 0.05 * reference[1], "overpressure at ×" + scale);
            assertEquals(reference[2], r[2], 0.05 * reference[2], "physical eruption rate at ×" + scale);
        }
    }

    @Test
    void retuningCompressionMidEruptionContinuesSmoothly() {
        MagmaChamberConfig slow = stromboli().eruptiveTimeScale(2).initialOverpressureMPa(7.9).build();
        MagmaChamber chamber = new MagmaChamber(slow);
        Engine engine = Engine.builder(0).add(chamber).build();
        for (int i = 0; i < 20 * 600; i++) engine.step();
        assertTrue(chamber.erupting());
        double rate = chamber.physicalEruptionRate();
        double pressure = chamber.overpressureMPa();

        // Tuning saves the world and reopens it with the new config (Session#reopenWorld).
        InMemorySaveStore saved = Saves.save(engine);
        MagmaChamber fast = new MagmaChamber(slow.toBuilder().eruptiveTimeScale(500).build());
        Engine reopened = Engine.builder(0).add(fast).restore(saved).allowConfigChanges().build();
        reopened.submit(new SetSupplyRate("v", 0.3));
        reopened.step();
        assertTrue(fast.erupting());
        assertEquals(pressure, fast.overpressureMPa(), 0.05 * pressure, "no pressure jump on retune");
        for (int i = 0; i < 20 * 60; i++) {
            reopened.step();
            assertTrue(fast.overpressureMPa() <= fast.ruptureOverpressureMPa() + 1e-9);
            assertTrue(fast.physicalEruptionRate() <= 1.5 * rate, "volcano-time rate does not jump with compression");
        }
    }

    @Test
    void ruptureStateSurvivesSaveAndRestore() {
        MagmaChamberConfig config = stromboli().supplyRate(0).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(7).add(chamber).build();
        engine.submit(new InjectRecharge("v", 2e8, 1150, 50, 1.0));
        for (int i = 0; i < 100; i++) engine.step();
        assertTrue(chamber.inelasticVolumeChangeM3() > 0);

        InMemorySaveStore saved = Saves.save(engine);
        MagmaChamber restored = new MagmaChamber(config);
        Engine again = Engine.builder(7).add(restored).restore(saved).build();
        assertEquals(chamber.inelasticVolumeChangeM3(), restored.inelasticVolumeChangeM3());
        assertEquals(chamber.volumeM3(), restored.volumeM3());
        for (int i = 0; i < 200; i++) {
            engine.step();
            again.step();
        }
        assertEquals(chamber.overpressureMPa(), restored.overpressureMPa());
        assertEquals(chamber.eruptedVolume(), restored.eruptedVolume());
    }
}
