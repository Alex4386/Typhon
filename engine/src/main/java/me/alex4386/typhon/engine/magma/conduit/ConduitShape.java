package me.alex4386.typhon.engine.magma.conduit;

import java.util.Arrays;

/**
 * Radius of a conduit along its length: {@code radii[0]} at the inlet (the chamber), the last at the vent,
 * nodes evenly spaced in between and linear between them. Immutable; equal shapes have equal radii.
 */
public final class ConduitShape {
    /** Nodes of an eroded conduit's profile (numerical resolution of where it widened). */
    public static final int NODES = 33;

    private final double[] radii;

    public ConduitShape(double[] radii) {
        if (radii.length < 2) throw new IllegalArgumentException("a shape needs at least two nodes");
        for (double r : radii) {
            if (!(r > 0)) throw new IllegalArgumentException("radii must be positive: " + r);
        }
        this.radii = radii.clone();
    }

    /** A cylinder of radius {@code r}. */
    public static ConduitShape uniform(double r) {
        double[] radii = new double[NODES];
        Arrays.fill(radii, r);
        return new ConduitShape(radii);
    }

    public int nodes() {
        return radii.length;
    }

    public double radius(int node) {
        return radii[node];
    }

    public double[] radii() {
        return radii.clone();
    }

    /** Node nearest to {@code fraction} of the length up from the inlet. */
    public int nearestNode(double fraction) {
        return (int) Math.max(0, Math.min(radii.length - 1, Math.rint(fraction * (radii.length - 1))));
    }

    /** Radius at {@code fraction} of the length up from the inlet (0) to the vent (1). */
    public double radiusAt(double fraction) {
        double x = Math.max(0, Math.min(1, fraction)) * (radii.length - 1);
        int i = Math.min(radii.length - 2, (int) x);
        return radii[i] + (radii[i + 1] - radii[i]) * (x - i);
    }

    /** d(radius)/d(fraction) on the segment holding {@code fraction}. */
    public double slopeAt(double fraction) {
        double x = Math.max(0, Math.min(1, fraction)) * (radii.length - 1);
        int i = Math.min(radii.length - 2, (int) x);
        return (radii[i + 1] - radii[i]) * (radii.length - 1);
    }

    /** Volume (m³) of a conduit of length {@code lengthM} with this shape (trapezoidal in the area). */
    public double volume(double lengthM) {
        double h = lengthM / (radii.length - 1);
        double sum = 0;
        for (int i = 0; i + 1 < radii.length; i++) sum += (radii[i] * radii[i] + radii[i + 1] * radii[i + 1]) / 2;
        return Math.PI * sum * h;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConduitShape s && Arrays.equals(radii, s.radii);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(radii);
    }

    @Override
    public String toString() {
        return "ConduitShape" + Arrays.toString(radii);
    }
}
