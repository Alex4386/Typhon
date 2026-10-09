package me.alex4386.typhon.engine.dike;

import static me.alex4386.typhon.engine.dike.DikeTestWorld.basalt;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.events;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.fastConfig;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.flat;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.run;
import static me.alex4386.typhon.engine.dike.DikeTestWorld.world;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands.InjectRecharge;
import me.alex4386.typhon.engine.output.EngineFrame;
import org.junit.jupiter.api.Test;

/** Magma beyond the chamber's rupture limit leaves through dikes; the walls only grow when no dike can take it. */
class WallRuptureDikeTest {
    private static final double INJECTED = 2e8;

    private static MagmaChamberConfig.Builder chamber() {
        return basalt(0).volume(1e9).tensileStrengthMPa(10);
    }

    /** Volume beyond the rupture limit for an injection of {@link #INJECTED} into an unpressurised chamber. */
    private static double excess(MagmaChamber c) {
        return INJECTED - c.ruptureOverpressureMPa() * c.volumeM3() * c.effectiveCompressibility();
    }

    private static double dikeVolume(DikePropagation dikes) {
        return dikes.dikes().stream().mapToDouble(Dike::volumeM3).sum();
    }

    @Test
    void ruptureOpensADikeThatCarriesTheExcessMagma() {
        DikeTestWorld.World w = world(1, chamber().build(), fastConfig(), flat(), null);
        w.engine().step();
        double excess = excess(w.chamber());
        assertTrue(excess > 0);
        w.engine().submit(new InjectRecharge("v", INJECTED, 1180, 50, 0.5, null, null));
        List<EngineFrame> frames = run(w.engine(), 100);

        assertFalse(events(frames, DikeEvents.DikeStarted.class).isEmpty(), "the ruptured walls start a dike");
        assertTrue(w.chamber().overpressureMPa() <= w.chamber().ruptureOverpressureMPa() + 1e-9);
        assertTrue(dikeVolume(w.dikes()) >= 0.99 * excess, "the dike carries the excess: " + dikeVolume(w.dikes()));
        assertTrue(w.chamber().inelasticVolumeChangeM3() < 0.01 * excess,
                "cold walls barely yield: " + w.chamber().inelasticVolumeChangeM3());
    }

    @Test
    void blockedDikesLeaveTheExcessAsChamberGrowth() {
        DikeTestWorld.World w = world(1, chamber().build(), fastConfig(), flat(), null);
        w.dikes().setNucleationBlocked(true);
        w.engine().step();
        double excess = excess(w.chamber());
        w.engine().submit(new InjectRecharge("v", INJECTED, 1180, 50, 0.5, null, null));
        List<EngineFrame> frames = run(w.engine(), 100);

        assertTrue(events(frames, DikeEvents.DikeStarted.class).isEmpty());
        assertEquals(excess, w.chamber().inelasticVolumeChangeM3(), 0.01 * excess);
    }

    @Test
    void theDikesOverrideBlocksRuptureDikesToo() {
        DikeConfig config = fastConfig();
        config.blocked = true;
        DikeTestWorld.World w = world(1, chamber().build(), config, flat(), null);
        w.engine().step();
        double excess = excess(w.chamber());
        w.engine().submit(new InjectRecharge("v", INJECTED, 1180, 50, 0.5, null, null));
        List<EngineFrame> frames = run(w.engine(), 100);

        assertTrue(events(frames, DikeEvents.DikeStarted.class).isEmpty());
        assertEquals(excess, w.chamber().inelasticVolumeChangeM3(), 0.01 * excess);
    }

    @Test
    void overriddenYieldingWallsAbsorbTheExcess() {
        DikeTestWorld.World w = world(1, chamber().wallYieldFraction(1).build(), fastConfig(), flat(), null);
        w.engine().step();
        double excess = excess(w.chamber());
        w.engine().submit(new InjectRecharge("v", INJECTED, 1180, 50, 0.5, null, null));
        List<EngineFrame> frames = run(w.engine(), 100);

        assertTrue(events(frames, DikeEvents.DikeStarted.class).isEmpty(), "nothing left for a dike");
        assertEquals(excess, w.chamber().inelasticVolumeChangeM3(), 0.01 * excess);
    }

    @Test
    void aFrozenChamberRefusesWhatNoDikeTakesAndNeverGrows() {
        // walls set to yield everything, but the size is frozen: nothing may grow the chamber
        DikeTestWorld.World w = world(1, chamber().wallYieldFraction(1).freezeVolume(true).build(), fastConfig(), flat(), null);
        w.dikes().setNucleationBlocked(true);
        w.engine().step();
        double volume = w.chamber().volumeM3();
        double excess = excess(w.chamber());
        w.engine().submit(new InjectRecharge("v", INJECTED, 1180, 50, 0.5, null, null));
        run(w.engine(), 100);

        assertEquals(volume, w.chamber().volumeM3(), 1e-6, "the frozen chamber keeps its size");
        assertEquals(0, w.chamber().wallGrowthM3(), 1e-9);
        assertEquals(excess, w.chamber().refusedVolumeM3(), 0.01 * excess, "the magma it could not hold is refused");
        assertTrue(w.chamber().overpressureMPa() <= w.chamber().ruptureOverpressureMPa() + 1e-9);
    }

    @Test
    void aFrozenChamberStillFeedsDikes() {
        DikeTestWorld.World w = world(1, chamber().freezeVolume(true).build(), fastConfig(), flat(), null);
        w.engine().step();
        double volume = w.chamber().volumeM3();
        double excess = excess(w.chamber());
        w.engine().submit(new InjectRecharge("v", INJECTED, 1180, 50, 0.5, null, null));
        List<EngineFrame> frames = run(w.engine(), 100);

        assertFalse(events(frames, DikeEvents.DikeStarted.class).isEmpty());
        assertTrue(dikeVolume(w.dikes()) >= 0.99 * excess);
        assertEquals(volume, w.chamber().volumeM3(), 1e-6);
        assertEquals(0, w.chamber().refusedVolumeM3(), 1e-6, "nothing refused while a dike carries it");
    }

    @Test
    void ruptureLimitIsComputedUnlessOverridden() {
        MagmaChamber computed = new MagmaChamber(chamber().build());
        assertEquals(2.0, computed.wallRuptureRatio(), 1e-12, "hoop stress at a spherical wall is half the overpressure");
        assertEquals(20, computed.ruptureOverpressureMPa(), 1e-9);
        MagmaChamber overridden = new MagmaChamber(chamber().wallRuptureRatio(1.5).build());
        assertEquals(1.5, overridden.wallRuptureRatio(), 1e-12);
        assertEquals(15, overridden.ruptureOverpressureMPa(), 1e-9);
    }

    @Test
    void hotSlowlyChargedWallsYieldMoreThanColdOnes() {
        MagmaChamber cold = new MagmaChamber(chamber().supplyRate(1).wallTemperatureC(400).build());
        MagmaChamber hot = new MagmaChamber(chamber().supplyRate(1).wallTemperatureC(900).build());
        MagmaChamber hotFast = new MagmaChamber(chamber().supplyRate(1000).wallTemperatureC(900).build());
        assertTrue(cold.wallYieldFraction() < 1e-3, "cold brittle walls fracture: " + cold.wallYieldFraction());
        assertTrue(hot.wallYieldFraction() > cold.wallYieldFraction());
        assertTrue(hotFast.wallYieldFraction() < hot.wallYieldFraction(), "fast charging outruns creep");
        for (MagmaChamber c : List.of(cold, hot, hotFast)) {
            assertTrue(c.wallYieldFraction() >= 0 && c.wallYieldFraction() <= 1);
        }
    }
}
