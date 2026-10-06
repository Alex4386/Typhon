package me.alex4386.typhon.engine.geomorph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.MaterialTable;
import org.junit.jupiter.api.Test;

/** Limit-equilibrium formulas against their closed forms, and the strength model's trends. */
class SlopeStabilityTest {
    private static final double GAMMA = 20_000;

    @Test
    void dryCohesionlessSlopeStandsAtItsFrictionAngle() {
        double phi = Math.toRadians(34);
        for (double deg : new double[] {10, 25, 34, 40, 60}) {
            double beta = Math.toRadians(deg);
            double fs = SlopeStability.infiniteSlope(0, Math.tan(phi), GAMMA, 3, beta, 0, 0);
            assertEquals(Math.tan(phi) / Math.tan(beta), fs, 1e-12, "FS = tanφ/tanβ at " + deg + "°");
        }
        assertEquals(34, Math.toDegrees(SlopeStability.criticalAngle(0, Math.tan(phi), GAMMA, 3, 0, 0)), 1e-6);
    }

    @Test
    void porePressureAndShakingMatchClosedForms() {
        double tanPhi = Math.tan(Math.toRadians(34));
        double beta = Math.toRadians(25);
        double tb = Math.tan(beta);
        double ru = 0.5;
        assertEquals((1 - ru) * tanPhi / tb, SlopeStability.infiniteSlope(0, tanPhi, GAMMA, 4, beta, ru, 0), 1e-12);
        double kh = 0.1;
        assertEquals((1 - kh * tb) * tanPhi / (tb + kh), SlopeStability.infiniteSlope(0, tanPhi, GAMMA, 4, beta, 0, kh),
                1e-12);
        // cohesion: c / (γ z sinβ cosβ) added
        double c = 10_000;
        double z = 2;
        double expected = (c + GAMMA * z * Math.cos(beta) * Math.cos(beta) * tanPhi)
                / (GAMMA * z * Math.sin(beta) * Math.cos(beta));
        assertEquals(expected, SlopeStability.infiniteSlope(c, tanPhi, GAMMA, z, beta, 0, 0), 1e-9);
    }

    @Test
    void culmannWedgeFailsAtItsCriticalHeight() {
        double c = 50_000;
        double phi = Math.toRadians(30);
        double beta = Math.toRadians(70);
        double hc = SlopeStability.culmannCriticalHeight(c, phi, GAMMA, beta);
        assertEquals(1.0, SlopeStability.culmann(c, Math.tan(phi), GAMMA, hc, beta, 0, 0), 0.01);
        assertTrue(SlopeStability.culmann(c, Math.tan(phi), GAMMA, 0.8 * hc, beta, 0, 0) > 1.1);
        assertTrue(SlopeStability.culmann(c, Math.tan(phi), GAMMA, 1.3 * hc, beta, 0, 0) < 0.9);
    }

    @Test
    void alterationHeatAndLoosenessWeakenRock() {
        LayerView fresh = new LayerView(0, 10, MaterialTable.ANDESITE.id(), 0, 0.08, 0, 1, 0);
        RockStrength.Strength f = RockStrength.of(fresh, 0, 15);
        RockStrength.Strength altered = RockStrength.of(fresh, 1, 15);
        assertTrue(altered.cohesionPa() < f.cohesionPa() / 10, "complete alteration: > tenfold cohesion loss");
        assertTrue(altered.frictionRad() < f.frictionRad());
        assertEquals(RockStrength.ALTERED_FRICTION_DEG, Math.toDegrees(altered.frictionRad()), 1e-9);

        LayerView flagged = new LayerView(0, 10, MaterialTable.ANDESITE.id(), 0, 0.08, 0, 1, LayerFlags.ALTERED);
        assertTrue(RockStrength.of(flagged, 0, 15).cohesionPa() < f.cohesionPa() / 3);

        RockStrength.Strength cracked = RockStrength.of(fresh, 0, 700);
        assertTrue(cracked.cohesionPa() < f.cohesionPa());
        double solidus = MaterialTable.ANDESITE.solidusC();
        double liquidus = MaterialTable.ANDESITE.liquidusC();
        RockStrength.Strength mush = RockStrength.of(fresh, 0, solidus + 0.5 * (liquidus - solidus));
        assertEquals(0, mush.cohesionPa(), 1e-9, "no frame at 50 % melt");
        assertEquals(0, mush.frictionRad(), 1e-9);

        LayerView scoria = new LayerView(0, 3, MaterialTable.SCORIA.id(), 0, 0.5, 0, 0, LayerFlags.LOOSE);
        RockStrength.Strength s = RockStrength.of(scoria, 0, 15);
        assertEquals(0, s.cohesionPa());
        assertEquals(34, Math.toDegrees(s.frictionRad()), 1e-9);
    }

    @Test
    void groundMotionFallsWithDistanceAndRisesWithMagnitude() {
        assertTrue(GroundMotion.pga(5, 0) > GroundMotion.pga(4, 0));
        assertTrue(GroundMotion.pga(5, 1) > GroundMotion.pga(5, 20));
        // Joyner & Boore (1981) at M 6.5, 10 km: log10 A = -1.02 + 1.6185 - log10(12.39) - 0.0316 ≈ -0.526
        assertEquals(Math.pow(10, -1.02 + 0.249 * 6.5 - Math.log10(Math.sqrt(100 + 53.29)) - 0.00255 * Math.sqrt(153.29)),
                GroundMotion.pga(6.5, 10), 1e-12);
        double r = GroundMotion.radiusKm(4, 0.01);
        assertEquals(0.01, GroundMotion.pga(4, r), 1e-6);
    }

    @Test
    void craterDiameterFollowsSatoTaniguchi() {
        // E = 4.45e6 D^3.05: one tonne of TNT (4.184e9 J) gives ≈ 9.4 m
        assertEquals(9.4, CraterScaling.diameter(4.184e9), 0.1);
        double ratio = CraterScaling.diameter(1e14) / CraterScaling.diameter(1e12);
        assertEquals(Math.pow(100, 1 / 3.05), ratio, 1e-9);
        assertEquals(0.25 * CraterScaling.diameter(1e12), CraterScaling.depth(1e12), 1e-12);
    }
}
