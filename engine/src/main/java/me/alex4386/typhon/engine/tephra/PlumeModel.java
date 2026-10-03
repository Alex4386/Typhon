package me.alex4386.typhon.engine.tephra;

/**
 * Eruption column height.
 *
 * <p>Mastin et al. (2009) empirical fit over historical eruptions:
 *
 * <pre>
 *   H = 2.00 · V̇^0.241   (H in km above the vent, V̇ in m³/s dense-rock equivalent)
 * </pre>
 *
 * Real columns (2–40 km) are far taller than a Minecraft world, so the engine multiplies the height
 * by {@link TephraConfig#plumeHeightScale} (default 0.01: 1 km → 10 blocks) and caps the top at
 * {@link TephraConfig#worldTopY}.
 */
public final class PlumeModel {
    private PlumeModel() {}

    /** Real plume height above the vent in metres for a DRE volume eruption rate (m³/s). */
    public static double realHeightMeters(double volumeEruptionRate) {
        if (volumeEruptionRate <= 0) return 0;
        return 2000.0 * StrictMath.pow(volumeEruptionRate, 0.241);
    }

    /** Inverse of {@link #realHeightMeters}: DRE volume rate (m³/s) for a plume height (m). */
    public static double volumeRateForHeight(double heightMeters) {
        if (heightMeters <= 0) return 0;
        return StrictMath.pow(heightMeters / 2000.0, 1 / 0.241);
    }

    /** Scaled plume height above the vent in blocks. */
    public static double minecraftHeight(double massEruptionRate, int ventY, TephraConfig config) {
        double real = realHeightMeters(massEruptionRate / ExplosivePhase.DRE_DENSITY);
        double scaled = real * config.plumeHeightScale;
        return Math.max(0, Math.min(scaled, config.worldTopY - ventY));
    }
}
