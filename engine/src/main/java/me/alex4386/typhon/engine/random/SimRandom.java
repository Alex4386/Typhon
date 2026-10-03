package me.alex4386.typhon.engine.random;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/**
 * Deterministic random source for the simulation.
 *
 * <p>All randomness in the engine must come from a {@code SimRandom}; {@code Math.random()} and
 * wall-clock seeding are forbidden so a run can be reproduced from its seed and inputs.
 *
 * <p>{@link #fork(String)} derives a child stream from this source's seed and a label only, so the
 * child sequence does not depend on how much the parent has been consumed or on the order in which
 * other forks were created. Adding a new subsystem therefore never perturbs existing ones.
 */
public final class SimRandom {
    private final long seed;
    private final SplittableRandom random;

    public SimRandom(long seed) {
        this.seed = seed;
        this.random = new SplittableRandom(seed);
    }

    public long seed() {
        return seed;
    }

    public SimRandom fork(String label) {
        return new SimRandom(mix64(seed ^ mix64(fnv1a64(label))));
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    /** Uniform in {@code [origin, bound)}. */
    public double nextDouble(double origin, double bound) {
        return random.nextDouble(origin, bound);
    }

    /** Uniform in {@code [0, bound)}. */
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    /** Uniform in {@code [origin, bound)}. */
    public int nextInt(int origin, int bound) {
        return random.nextInt(origin, bound);
    }

    public long nextLong() {
        return random.nextLong();
    }

    public boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** Standard normal sample (Box–Muller; deterministic, no cached spare). */
    public double nextGaussian() {
        double u1;
        do {
            u1 = random.nextDouble();
        } while (u1 <= Double.MIN_VALUE);
        double u2 = random.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    public double nextGaussian(double mean, double stdDev) {
        return mean + nextGaussian() * stdDev;
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
