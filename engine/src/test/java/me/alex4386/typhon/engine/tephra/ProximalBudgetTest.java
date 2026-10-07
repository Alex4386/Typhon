package me.alex4386.typhon.engine.tephra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

/**
 * Proximal fallout (fountain fall-back, Surtseyan jets) keeps its mass, lands where ballistics put it and
 * thins away from the vent the way proximal deposits do.
 */
class ProximalBudgetTest {
    /** Ground elevation (m) of the 1 m-column test ground. */
    private static final double GROUND = 100;
    private static final VentSite VENT = VentSite.crater("summit", new Point3(0.5, GROUND, 0.5), 2);

    private record Setup(Engine engine, TephraSubsystem tephra, WorldModel world) {}

    private static Setup setup() {
        TephraConfig c = new TephraConfig();
        TerrainModel terrain = TestGround.terrain(1.0);
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, c);
        Engine engine = Engine.builder(5).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(24, GROUND));
        engine.step();
        return new Setup(engine, tephra, terrain.world());
    }

    private static Setup erupt(double massKg, double speed, double zenithMeanDeg, double medianM) {
        Setup s = setup();
        for (int i = 0; i < 120; i++) {
            s.tephra().proximalFallout(VENT, massKg / 120, speed, zenithMeanDeg, 15, medianM, 2e-3, 0.064);
            s.engine().step();
        }
        for (int i = 0; i < 20; i++) s.engine().step();
        return s;
    }

    /** Mean deposit thickness (m) on the world in 1 m rings [r, r+1) around the vent, out to {@code n}. */
    private static double[] rings(Setup s, int n) {
        double[] sum = new double[n];
        int[] count = new int[n];
        WorldModel w = s.world();
        for (int x = -n; x <= n; x++) {
            for (int z = -n; z <= n; z++) {
                int r = (int) Math.hypot(x, z);
                if (r >= n) continue;
                sum[r] += w.surfaceZ(x, z) - GROUND;
                count[r]++;
            }
        }
        for (int r = 0; r < n; r++) sum[r] /= Math.max(1, count[r]);
        return sum;
    }

    @Test
    void proximalMassAllEndsUpOnTheGround() {
        double mass = 4e6;
        Setup s = erupt(mass, 80, 40, 8e-3);
        TephraSubsystem.MassBudget b = s.tephra().massBudget();
        assertEquals(mass, b.emitted(), 1e-6 * mass, "every parcel is booked: " + b);
        assertEquals(b.emitted(), b.deposited() + b.airborne() + b.exported() + b.discarded(), 1e-6 * mass, "budget closes: " + b);
        double volume = 0;
        WorldModel w = s.world();
        int n = 24 * 16;
        for (int x = -n; x < n; x++) for (int z = -n; z < n; z++) volume += w.surfaceZ(x, z) - GROUND;
        double onGround = volume * TephraSubsystem.PROXIMAL_BULK_DENSITY;
        assertEquals(b.deposited(), onGround, 1e-3 * mass, "what is booked as deposited lies on the world: " + onGround);
    }

    /** Solid mass (kg) of the bomb heaps on the ground: volume over bulk density of the heap. */
    private static double bombMass(Setup s) {
        double volume = 0;
        WorldModel w = s.world();
        int n = 24 * 16;
        for (int x = -n; x < n; x++) for (int z = -n; z < n; z++) volume += w.surfaceZ(x, z) - GROUND;
        return volume * (1 - TephraSubsystem.BOMB_HEAP_POROSITY) * new TephraConfig().bombDensity;
    }

    @Test
    void cappedSalvosLandAllTheirMassAndShowOnlySalvosNone() {
        // 5e5 kg is thousands of mean bombs: the salvo tracks 10 and each stands for the rest
        Setup carried = setup();
        carried.tephra().launchSalvo(VENT, 5e5, 60, 0, 20, 50, 10, true);
        for (int i = 0; i < 20 * 60; i++) carried.engine().step();
        assertEquals(5e5, bombMass(carried), 0.01 * 5e5, "the whole ballistic mass lands");

        Setup shown = setup();
        shown.tephra().launchSalvo(VENT, 5e5, 60, 0, 20, 50, 10, false);
        for (int i = 0; i < 20 * 60; i++) shown.engine().step();
        assertEquals(0, bombMass(shown), 1e-6, "a salvo that only shows laid-down ejecta adds nothing");
    }

    @Test
    void jetEjectaLandWithinBallisticRange() {
        // Surtseyan jets leave at ~60–100 m/s (cock's tails 200–500 m high: Thorarinsson 1967; Moore 1985);
        // lapilli in them land within the drag-free range v²/g and mostly well beyond the crater.
        double speed = 80;
        Setup s = erupt(4e6, speed, 40, 8e-3);
        double[] ring = rings(s, 380);
        double mass = 0, moment = 0;
        int far = 0;
        for (int r = 0; r < ring.length; r++) {
            double m = ring[r] * 2 * Math.PI * (r + 0.5);
            mass += m;
            moment += m * r;
            if (ring[r] > 1e-6) far = r;
        }
        double mean = moment / mass;
        double maxRange = speed * speed / 9.81;
        assertTrue(far < 1.2 * maxRange, "nothing beyond the drag-free range: " + far + " vs " + maxRange);
        assertTrue(mean > 3 * VENT.craterRadiusM(), "builds a ring, not a spike in the crater: mean " + mean);
        System.out.printf("jet ejecta at %.0f m/s: mean range %.0f m, farthest %d m%n", speed, mean, far);
    }

    @Test
    void proximalThicknessThinsOutwardsExponentially() {
        // Proximal fall thins roughly exponentially with distance (Pyle 1989): thickness falls monotonically
        // beyond the deposit's crest and its log decays steadily, not in a step.
        Setup s = erupt(4e6, 80, 40, 8e-3);
        double[] ring = rings(s, 380);
        int crest = 0;
        for (int r = 0; r < ring.length; r++) if (ring[r] > ring[crest]) crest = r;
        // smooth over 5 m bins, then each bin past the crest is thinner than the last
        int bin = 10;
        double previous = Double.POSITIVE_INFINITY;
        int bins = 0;
        for (int r0 = crest + bin; r0 + bin < ring.length; r0 += bin) {
            double t = 0;
            for (int r = r0; r < r0 + bin; r++) t += ring[r];
            t /= bin;
            if (t < 1e-3 * ring[crest]) break;
            assertTrue(t <= previous * 1.25, "thinning outwards at " + r0 + " m: " + t + " after " + previous);
            previous = t;
            bins++;
        }
        assertTrue(bins >= 4, "the deposit has a proximal apron, not a single ring: " + bins + " bins past crest " + crest);
        System.out.printf("proximal profile: crest %d m (%.3f m), apron %d m%n", crest, ring[crest], bins * bin);
    }
}
