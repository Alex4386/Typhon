package me.alex4386.typhon.engine.random;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic random source for the simulation (SplitMix64).
 *
 * <p>All randomness in the engine must come from a {@code SimRandom}; {@code Math.random()} and
 * wall-clock seeding are forbidden so a run can be reproduced from its seed and inputs. The whole
 * generator state is a single {@code long} ({@link #state()}), so it can be saved and restored to
 * resume a run bit-for-bit.
 *
 * <p>{@link #fork(String)} derives a child stream from this source's seed and a label only, so the
 * child sequence does not depend on how much the parent has been consumed or on the order in which
 * other forks were created. Adding a new subsystem therefore never perturbs existing ones.
 */
public final class SimRandom {
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private final long seed;
    private long state;

    public SimRandom(long seed) {
        this.seed = seed;
        this.state = seed;
    }

    public long seed() {
        return seed;
    }

    /** Current generator state; pass to {@link #restore(long)} to resume the sequence. */
    public long state() {
        return state;
    }

    public void restore(long state) {
        this.state = state;
    }

    public SimRandom fork(String label) {
        return new SimRandom(mix64(seed ^ mix64(fnv1a64(label))));
    }

    public long nextLong() {
        state += GOLDEN_GAMMA;
        return mix64(state);
    }

    /** Uniform in {@code [0, 1)}. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in {@code [origin, bound)}. */
    public double nextDouble(double origin, double bound) {
        if (!(origin < bound)) throw new IllegalArgumentException("origin must be < bound");
        double r = origin + nextDouble() * (bound - origin);
        return r < bound ? r : Math.nextDown(bound);
    }

    /** Uniform in {@code [0, bound)}, unbiased. */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        // Lemire's multiply-shift with rejection
        long m = (nextLong() >>> 32) * bound;
        long low = m & 0xffffffffL;
        if (low < bound) {
            long threshold = (0x100000000L - bound) % bound;
            while (low < threshold) {
                m = (nextLong() >>> 32) * bound;
                low = m & 0xffffffffL;
            }
        }
        return (int) (m >>> 32);
    }

    /** Uniform in {@code [origin, bound)}. */
    public int nextInt(int origin, int bound) {
        if (origin >= bound) throw new IllegalArgumentException("origin must be < bound");
        long range = (long) bound - origin;
        if (range <= Integer.MAX_VALUE) return origin + nextInt((int) range);
        int r;
        do {
            r = (int) nextLong();
        } while (r < origin || r >= bound);
        return r;
    }

    public boolean chance(double probability) {
        return nextDouble() < probability;
    }

    /** Standard normal sample (Box–Muller; deterministic, no cached spare). */
    public double nextGaussian() {
        double u1;
        do {
            u1 = nextDouble();
        } while (u1 <= Double.MIN_VALUE);
        double u2 = nextDouble();
        return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * Math.PI * u2);
    }

    public double nextGaussian(double mean, double stdDev) {
        return mean + nextGaussian() * stdDev;
    }

    /** Exponentially distributed sample with the given rate (events per unit). */
    public double nextExponential(double rate) {
        if (rate <= 0) throw new IllegalArgumentException("rate must be positive");
        return -StrictMath.log(1.0 - nextDouble()) / rate;
    }

    /** Poisson-distributed count with the given mean (Knuth for small means, normal approx above). */
    public int nextPoisson(double mean) {
        if (mean < 0) throw new IllegalArgumentException("mean must be non-negative");
        if (mean == 0) return 0;
        if (mean > 30) {
            return Math.max(0, (int) Math.round(nextGaussian(mean, StrictMath.sqrt(mean))));
        }
        double limit = StrictMath.exp(-mean);
        int k = 0;
        double p = nextDouble();
        while (p > limit) {
            k++;
            p *= nextDouble();
        }
        return k;
    }

    // SplitMix64 finalizer
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
