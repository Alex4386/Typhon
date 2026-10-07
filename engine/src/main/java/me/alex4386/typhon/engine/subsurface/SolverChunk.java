package me.alex4386.typhon.engine.subsurface;

/**
 * State of 16×16 solver columns of the subsurface grid, as flat arrays.
 *
 * <p>Column index {@code c = (gz & 15) * 16 + (gx & 15)}; level arrays are indexed
 * {@code c * levels + k} with {@code k = 0} the top level. Persistent state: {@link #initialized},
 * {@link #temperature}, {@link #steam}, {@link #head}, {@link #vadose}, {@link #steamFlux}. Everything
 * else is derived from the world model and recomputed after a load (single precision: material
 * properties are known to a few percent at best).
 */
final class SolverChunk {
    static final int SIZE = 16;
    static final int AREA = SIZE * SIZE;

    final int cx;
    final int cz;
    final int levels;

    // ── Persistent state ──
    /** Column has been seen and its state initialised. */
    final boolean[] initialized = new boolean[AREA];
    /** Temperature (°C) per cell. */
    final double[] temperature;
    /** Steam fraction of the pore space per cell, 0–1. */
    final double[] steam;
    /** Water-table elevation (m). */
    final double[] head = new double[AREA];
    /** Water held in the vadose zone, as an equivalent depth (m) over the column area. */
    final double[] vadose = new double[AREA];
    /** Steam mass flux leaving the top of the column during the last macro step (kg/s). */
    final double[] steamFlux = new double[AREA];

    // ── Derived from the world model ──
    /** Column currently has at least one known surface column. */
    final boolean[] exists = new boolean[AREA];
    /** {@link me.alex4386.typhon.engine.world.ColumnStacks#footprint} the column's properties were last computed from. */
    final long[] footprint = new long[AREA];
    /** Whether {@link #footprint} holds a value (cleared to force a recomputation). */
    final boolean[] footprintValid = new boolean[AREA];
    /** Mean ground elevation of the known surface columns (m). */
    final double[] surfaceZ = new double[AREA];
    /** Known surface columns in the block. */
    final int[] knownColumns = new int[AREA];
    /** Lowest known surface column (spring outlet), packed as x/z. */
    final int[] outletX = new int[AREA];
    final int[] outletZ = new int[AREA];
    /** Column lies below sea level (fixed head). */
    final boolean[] sea = new boolean[AREA];
    /** Standing-water elevation imported with the terrain (m), NaN if none. */
    final double[] lakeZ = new double[AREA];
    /** Thermal conductivity (W/m·K), volumetric heat capacity of the solid frame (J/m³·K). */
    final float[] conductivity;
    final float[] heatCapacity;
    /** Porosity (0–1), hydraulic conductivity (m/s), melt interval (°C, NaN if none), bulk density. */
    final float[] porosity;
    final float[] hydraulicK;
    final float[] solidus;
    final float[] liquidus;
    final float[] density;

    // ── Level of detail (persisted in the subsystem JSON) ──
    Activity activity = Activity.DORMANT;
    /** Largest |ΔT| (°C) of any cell during the chunk's last heat step (∞ until stepped). */
    double lastChange = Double.POSITIVE_INFINITY;
    int quietSteps;
    int warmCounter;

    enum Activity { DORMANT, WARM, HOT }

    SolverChunk(int cx, int cz, int levels) {
        this.cx = cx;
        this.cz = cz;
        this.levels = levels;
        int n = AREA * levels;
        temperature = new double[n];
        steam = new double[n];
        conductivity = new float[n];
        heatCapacity = new float[n];
        porosity = new float[n];
        hydraulicK = new float[n];
        solidus = new float[n];
        liquidus = new float[n];
        density = new float[n];
        java.util.Arrays.fill(lakeZ, Double.NaN);
    }

    static int column(int gx, int gz) {
        return (Math.floorMod(gz, SIZE) << 4) | Math.floorMod(gx, SIZE);
    }

    int gx(int c) {
        return cx * SIZE + (c & 15);
    }

    int gz(int c) {
        return cz * SIZE + (c >> 4);
    }

    boolean anyExists() {
        for (boolean e : exists) if (e) return true;
        return false;
    }
}
