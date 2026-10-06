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
    void sustainedSupplyWithLongStepsStaysBounded() {
        // A year with hour-long quiet steps, plus a 10x supply surge after a month.
        MagmaChamberConfig config = stromboli().build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine engine = Engine.builder(0).adaptive(3600).add(chamber).build();
        double peakP = 0, peakQ = 0;
        boolean surged = false;
        while (engine.time() < 365 * 86_400.0) {
            if (!surged && engine.time() >= 30 * 86_400.0) {
                engine.submit(new SetSupplyRate("v", 3.0));
                surged = true;
            }
            engine.step();
            peakP = Math.max(peakP, chamber.overpressureMPa());
            peakQ = Math.max(peakQ, chamber.eruptionRate());
            assertTrue(Double.isFinite(chamber.overpressureMPa()));
        }
        assertTrue(peakP <= chamber.ruptureOverpressureMPa() + 1e-9, "peak overpressure " + peakP);
        assertTrue(chamber.eruptionCount() > 0, "the chamber erupts");
        assertTrue(peakQ < 1e3, "the eruption rate stays volcanic, not runaway: " + peakQ);
    }

    /** Erupted volume, overpressure and rate after {@code seconds} of eruption with steps up to {@code maxStep}. */
    private static double[] eruptWithSteps(double maxStep, double seconds) {
        MagmaChamberConfig config = stromboli().initialOverpressureMPa(7.9).build();
        MagmaChamber chamber = new MagmaChamber(config);
        Engine.Builder b = Engine.builder(0).add(chamber);
        if (maxStep > 0) b.adaptive(maxStep);
        Engine engine = b.build();
        double since = Double.NaN;
        while (!(since >= seconds)) {
            engine.step();
            if (chamber.erupting()) since = Double.isNaN(since) ? 0 : since + engine.lastStepSeconds();
            assertTrue(chamber.overpressureMPa() <= chamber.ruptureOverpressureMPa() + 1e-9);
        }
        return new double[] {chamber.eruptedVolume(), chamber.overpressureMPa(), chamber.eruptionRate()};
    }

    @Test
    void eruptionConvergesAcrossStepLengths() {
        double seconds = 3 * 3600;
        double[] reference = eruptWithSteps(0, seconds); // fixed 50 ms steps
        assertTrue(reference[2] > 0.1 && reference[2] < 100, "a Strombolian-scale effusion rate: " + reference[2]);
        for (double maxStep : new double[] {20, 120, 3600}) {
            double[] r = eruptWithSteps(maxStep, seconds);
            assertEquals(reference[0], r[0], 0.05 * reference[0], "erupted volume with steps up to " + maxStep + " s");
            assertEquals(reference[1], r[1], 0.05 * reference[1], "overpressure with steps up to " + maxStep + " s");
            assertEquals(reference[2], r[2], 0.05 * reference[2], "eruption rate with steps up to " + maxStep + " s");
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
