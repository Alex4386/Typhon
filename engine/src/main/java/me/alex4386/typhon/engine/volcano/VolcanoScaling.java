package me.alex4386.typhon.engine.volcano;

/**
 * Maps real-world volcanic magnitudes onto a Minecraft-sized volcano.
 *
 * <p>Subsystems compute physics in real units (m, m³/s, MPa, °C). The scaling only converts their
 * outputs into the model world, following Froude similarity for gravity-driven phenomena: with
 * {@code L} real metres per block, lengths scale by {@code 1/L}, volumes by {@code 1/L³} and
 * velocities by {@code 1/√L} (so ballistic ranges, ∝ v²/g, also scale by {@code 1/L}).
 *
 * <p>The atmosphere gets its own length scale because eruption columns are kilometres tall while
 * the world is only a few hundred blocks high. Time compression is a gameplay choice: dormant
 * phases are sped up so unrest builds over in-game hours rather than years.
 *
 * @param metersPerBlock real metres represented by one block of edifice/flow geometry
 * @param plumeMetersPerBlock real metres represented by one block of eruption-column height
 * @param dormantTimeCompression real seconds simulated per engine second while not erupting
 * @param eruptiveTimeCompression real seconds simulated per engine second while erupting
 */
public record VolcanoScaling(
        double metersPerBlock,
        double plumeMetersPerBlock,
        double dormantTimeCompression,
        double eruptiveTimeCompression) {

    /** A small stratovolcano: 1 block = 4 m, columns 1 block = 100 m, dormancy ×5000. */
    public static final VolcanoScaling DEFAULT = new VolcanoScaling(4, 100, 5000, 1);

    /** 1:1 physics, no compression (tests and reference runs). */
    public static final VolcanoScaling REAL = new VolcanoScaling(1, 1, 1, 1);

    public VolcanoScaling {
        requirePositive("metersPerBlock", metersPerBlock);
        requirePositive("plumeMetersPerBlock", plumeMetersPerBlock);
        requirePositive("dormantTimeCompression", dormantTimeCompression);
        requirePositive("eruptiveTimeCompression", eruptiveTimeCompression);
    }

    /** Real m³ → model blocks. */
    public double volumeScale() {
        return 1.0 / (metersPerBlock * metersPerBlock * metersPerBlock);
    }

    /** Real m → model blocks. */
    public double lengthScale() {
        return 1.0 / metersPerBlock;
    }

    /** Real m/s → model blocks/s (Froude). */
    public double velocityScale() {
        return 1.0 / Math.sqrt(metersPerBlock);
    }

    /** Real column height (m) → blocks. */
    public double plumeHeightScale() {
        return 1.0 / plumeMetersPerBlock;
    }

    public VolcanoScaling withMetersPerBlock(double value) {
        return new VolcanoScaling(value, plumeMetersPerBlock, dormantTimeCompression, eruptiveTimeCompression);
    }

    public VolcanoScaling withTimeCompression(double dormant, double eruptive) {
        return new VolcanoScaling(metersPerBlock, plumeMetersPerBlock, dormant, eruptive);
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be > 0: " + value);
    }
}
