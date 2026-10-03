package me.alex4386.typhon.simulator.terrain;

/** Deterministic, seeded 2D value noise with fractal octaves (no global state, no Math.random). */
public final class ValueNoise {
    private final long seed;

    public ValueNoise(long seed) {
        this.seed = seed;
    }

    /** Fractal noise in roughly [-1, 1] with feature size {@code scale} blocks. */
    public double fbm(double x, double z, double scale, int octaves) {
        double sum = 0;
        double amplitude = 1;
        double norm = 0;
        double frequency = 1.0 / scale;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * sample(x * frequency, z * frequency, o);
            norm += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        return sum / norm;
    }

    /** Single-octave smooth value noise in [-1, 1]. */
    public double sample(double x, double z, int octave) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = smooth(x - x0);
        double tz = smooth(z - z0);
        double a = lattice(x0, z0, octave);
        double b = lattice(x0 + 1, z0, octave);
        double c = lattice(x0, z0 + 1, octave);
        double d = lattice(x0 + 1, z0 + 1, octave);
        double top = a + (b - a) * tx;
        double bottom = c + (d - c) * tx;
        return top + (bottom - top) * tz;
    }

    private double lattice(int x, int z, int octave) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (octave * 0x165667B19E3779F9L);
        h = mix64(h);
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
