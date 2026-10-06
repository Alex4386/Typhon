package me.alex4386.typhon.engine.magma.plumbing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands.StartEruption;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.testing.Saves;
import org.junit.jupiter.api.Test;

/** Magma moving between a deep and a shallow chamber along a conduit. */
class MagmaTransferTest {
    private static final double TICK = 0.05;
    private static final double METERS_PER_BLOCK = 10;

    /** A basaltic system: a shallow main chamber at 1.5 km over a deep one at 4 km, no own supply. */
    private static MagmaChamberConfig.Builder main() {
        return MagmaChamberConfig.builder("v", new BlockPos(0, 0, 0)).volume(1e9).lithostaticDepth(1500).tensileStrengthMPa(10)
                .eruptionEndOverpressureMPa(1).supplyRate(0).supplyVariability(0).initialSilicaWt(50).initialWaterWt(0.4)
                .initialTemperatureC(1180).dormantTimeScale(5000).eruptiveTimeScale(20);
    }

    private static MagmaChamberConfig.Builder deep() {
        return main().chamberId("deep").volume(1e10).lithostaticDepth(4000).tensileStrengthMPa(30);
    }

    private record Sys(Engine engine, MagmaChamber main, MagmaChamber deep, MagmaTransfer transfer) {}

    private static Sys build(MagmaChamberConfig mainConfig, MagmaChamberConfig deepConfig, ConnectionConfig link, int threads, InMemorySaveStore restore) {
        MagmaChamber m = new MagmaChamber(mainConfig);
        MagmaChamber d = new MagmaChamber(deepConfig);
        d.setEruptive(false);
        MagmaTransfer t = new MagmaTransfer("v", Map.of(MagmaChamberConfig.MAIN, m, "deep", d), m, List.of(link), METERS_PER_BLOCK);
        Engine.Builder b = Engine.builder(3).threads(threads).add(m).add(d).add(t);
        if (restore != null) b.restore(restore);
        return new Sys(b.build(), m, d, t);
    }

    private static ConnectionConfig conduit(double radius) {
        ConnectionConfig c = ConnectionConfig.of("deep-main", "deep", MagmaChamberConfig.MAIN, ConnectionConfig.Kind.CONDUIT);
        return new ConnectionConfig(c.id(), c.from(), c.to(), c.kind(), radius, c.widthM(), c.strikeLengthM(), c.lengthM(), true, false,
                c.stallRateM3PerS(), c.freezeSeconds());
    }

    private static void run(Engine e, int steps) {
        for (int i = 0; i < steps; i++) e.step();
    }

    @Test
    void twoChambersSettleAtHeadEquilibriumAndConserveVolume() {
        Sys s = build(main().build(), deep().initialOverpressureMPa(8).build(), conduit(1.5), 1, null);
        ConnectionConfig link = s.transfer().connections().get(0);
        double drive0 = s.transfer().drivingPressureMPa(link);
        assertTrue(drive0 > 8, "the deep chamber's overpressure plus the denser crust push magma up: " + drive0);
        run(s.engine(), 20 * 3000); // ~3000 chamber steps of 5000 physical s
        double drive = s.transfer().drivingPressureMPa(link);
        assertTrue(Math.abs(drive) < 0.02 * drive0, "flow stops at head equilibrium: " + drive);
        assertTrue(s.main().overpressureMPa() > 0, "the shallow chamber is pressurised from below");
        assertEquals(s.deep().transferredOutM3(), s.main().transferredInM3(), 1e-6, "what leaves arrives");
        assertTrue(s.main().transferredInM3() > 0);
        // no overshoot: the drive never reverses (one-way flow, exact relaxation)
        assertTrue(drive >= -1e-6, "no overshoot past equilibrium: " + drive);
    }

    @Test
    void deepRechargeReachesTheShallowChamberSoonerThroughAWiderConduit() {
        double narrow = timeToPressurise(0.8);
        double wide = timeToPressurise(1.6);
        assertTrue(wide < narrow, "a wider conduit (C ∝ r⁴) transmits the deep recharge sooner: " + wide + " vs " + narrow);
    }

    /** Steps until the main chamber gains 1 MPa while only the deep chamber is supplied. */
    private static double timeToPressurise(double radius) {
        Sys s = build(main().build(), deep().supplyRate(5).build(), conduit(radius), 1, null);
        // start at head equilibrium (no flow), so only the recharge drives the main chamber
        for (int i = 0; i < 200_000; i++) {
            s.engine().step();
            if (s.main().overpressureMPa() >= 1) return i;
        }
        return Double.POSITIVE_INFINITY;
    }

    @Test
    void anEruptionOfTheShallowChamberDrawsDownTheDeepOne() {
        // basalt is denser than this crust: 2.5 km of magma column holds back ~2.45 MPa, so the deep chamber
        // starts at head equilibrium with the shallow one (no flow until the shallow one loses pressure)
        // one clock for both runs (quiet and erupting cover the same physical time)
        MagmaChamberConfig deepCfg = deep().initialOverpressureMPa(12).dormantTimeScale(20).build();
        MagmaChamberConfig mainCfg = main().initialOverpressureMPa(9.5).dormantTimeScale(20).build();
        Sys quiet = build(mainCfg, deepCfg, conduit(1.5), 1, null);
        Sys erupting = build(mainCfg, deepCfg, conduit(1.5), 1, null);
        erupting.engine().submit(new StartEruption("v"));
        run(quiet.engine(), 20 * 2000);
        run(erupting.engine(), 20 * 2000);
        assertTrue(erupting.main().eruptedVolume() > 0, "the main chamber erupted");
        assertTrue(erupting.deep().transferredOutM3() > quiet.deep().transferredOutM3(),
                "the deep chamber resupplies the erupting one: " + erupting.deep().transferredOutM3() + " vs " + quiet.deep().transferredOutM3());
        assertTrue(erupting.deep().overpressureMPa() < quiet.deep().overpressureMPa(), "and is drawn down");
        assertFalse(erupting.deep().erupting(), "a deep chamber never erupts by itself");
    }

    @Test
    void aClosedPathwayCarriesNothing() {
        Sys s = build(main().build(), deep().initialOverpressureMPa(8).build(), conduit(1.5).withOpen(false), 1, null);
        run(s.engine(), 20 * 100);
        assertEquals(0, s.main().transferredInM3());
    }

    @Test
    void transfersRestoreBitForBitAndIgnoreThreadCount() {
        MagmaChamberConfig deepCfg = deep().supplyRate(3).initialOverpressureMPa(4).build();
        Sys reference = build(main().build(), deepCfg, conduit(1.2), 1, null);
        run(reference.engine(), 20 * 300);
        InMemorySaveStore saved = Saves.save(reference.engine());
        run(reference.engine(), 20 * 300);

        Sys restored = build(main().build(), deepCfg, conduit(1.2), 1, saved);
        run(restored.engine(), 20 * 300);
        assertEquals(reference.engine().stateHash(), restored.engine().stateHash(), "restore is exact");

        Sys threaded = build(main().build(), deepCfg, conduit(1.2), 4, null);
        run(threaded.engine(), 20 * 600);
        assertEquals(reference.engine().stateHash(), threaded.engine().stateHash(), "thread count does not matter");
    }

    @Test
    void conductanceFollowsPoiseuille() {
        Sys s = build(main().build(), deep().build(), conduit(1.0), 1, null);
        ConnectionConfig c = s.transfer().connections().get(0);
        double c1 = s.transfer().conductance(c);
        double c2 = s.transfer().conductance(conduit(2.0));
        assertEquals(16, c2 / c1, 1e-9, "a pipe's conductance goes as r⁴");
        assertEquals(2500, s.transfer().lengthM(c), 1e-9, "the path runs between the chamber depths");
        ConnectionConfig slot = new ConnectionConfig("s", "deep", MagmaChamberConfig.MAIN, ConnectionConfig.Kind.DIKE, 1, 2, 500,
                Double.NaN, true, false, 0.01, 1e7);
        double cs = s.transfer().conductance(slot);
        double eta = Math.pow(10, s.deep().viscosityLog10());
        assertEquals(8.0 * 500 / (12 * eta * 2500), cs, 1e-12 * cs, "a slot's conductance goes as w³ℓ");
    }
}
