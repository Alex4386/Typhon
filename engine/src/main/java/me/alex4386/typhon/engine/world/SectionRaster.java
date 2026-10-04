package me.alex4386.typhon.engine.world;

/**
 * A vertical cross-section sampled along a polyline: {@code nu} sample columns along the line
 * (distance {@code u}), {@code nz} rows from {@code zMax} (row 0) down to {@code zMin}. Pixel
 * {@code (iu, iz)} is at index {@code iz * nu + iu}.
 *
 * @param uMeters distance along the line of each sample column (m)
 * @param surfaceZ ground surface at each sample column (m), {@code NaN} where unknown
 * @param material material id per pixel ({@link MaterialTable#AIR} above ground or where unknown)
 * @param unit unit id per pixel, {@code -1} above ground or where unknown
 * @param voidFraction sub-cell void fraction per pixel, 0–255
 */
public record SectionRaster(int nu, int nz, double zMin, double zMax, double[] uMeters, float[] surfaceZ,
        short[] material, int[] unit, byte[] voidFraction) {
    public int index(int iu, int iz) {
        return iz * nu + iu;
    }

    /** Elevation of the centre of row {@code iz}. */
    public double rowZ(int iz) {
        return zMax - (iz + 0.5) * (zMax - zMin) / nz;
    }
}
