package me.alex4386.typhon.engine.deformation;

/**
 * Surface displacement in real metres. East is +X; north is −Z (Minecraft's north); up is +Y.
 */
public record Displacement(double east, double north, double up) {
    public static final Displacement ZERO = new Displacement(0, 0, 0);

    public Displacement plus(Displacement other) {
        return new Displacement(east + other.east, north + other.north, up + other.up);
    }

    public double horizontal() {
        return Math.hypot(east, north);
    }
}
