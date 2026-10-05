package me.alex4386.typhon.engine.lava;

import me.alex4386.typhon.engine.terrain.TerrainChunkView;

/** Lava state of one 16×16 chunk as flat primitive arrays, indexed {@code (z & 15) * 16 + (x & 15)}. */
final class LavaChunk {
    static final int AREA = 256;
    static final int UNKNOWN = Integer.MIN_VALUE;
    /** Flow directions: 4 orthogonal then 4 diagonal, ordered so that {@code d ^ 1} is the opposite. */
    static final int DIRECTIONS = 8;

    final int cx;
    final int cz;
    final long key;

    // Simulation state (persisted)
    double[] thickness = new double[AREA]; // molten core (real m)
    double[] temperature = new double[AREA];
    double[] silica = new double[AREA];
    double[] water = new double[AREA];
    final double[] solid = new double[AREA]; // solidified rock not yet amounting to a whole block (real m)
    final double[] crust = new double[AREA]; // rigid crust/roof on top of the melt (real m)
    final double[] roofTop = new double[AREA]; // absolute elevation of the crust top, real m (= y · L)
    final byte[] crustKind = new byte[AREA]; // LavaPalette.crustKind of the crust
    int[] unit = new int[AREA]; // stratigraphic unit (eruption) of the melt; kept after it drains (roofs, films)

    // What is currently shown in the world (persisted, for compare-and-set diffs). From renderBottom
    // upward: renderMelt lava blocks, renderGap air blocks, renderRoof roof blocks.
    final int[] renderBottom = new int[AREA];
    final short[] renderMelt = new short[AREA];
    final short[] renderGap = new short[AREA];
    final short[] renderRoof = new short[AREA];
    final byte[] renderTop = new byte[AREA]; // top melt block: kind << 3 | level; kind 1 lava, 2 magma crust
    final byte[] renderRoofKind = new byte[AREA];

    // Double buffers and per-step scratch (transient)
    double[] nextThickness = new double[AREA];
    double[] nextTemperature = new double[AREA];
    double[] nextSilica = new double[AREA];
    double[] nextWater = new double[AREA];
    int[] nextUnit = new int[AREA];
    final double[] outflow = new double[DIRECTIONS * AREA]; // direction-major, as thickness (m)
    final double[] speed = new double[AREA]; // physical mean flow speed q/h this step (m/s)
    final int[] ground = new int[AREA];
    final int[] waterY = new int[AREA];
    /** World-model ground surface + uplift (real m), NaN where unknown: the bed the lava flows on. */
    final double[] bed = new double[AREA];
    long bedVersion = Long.MIN_VALUE; // world version sum the bed cache was read at
    final long[] sourceStamp = new long[AREA]; // == step stamp while an effusive source feeds the cell
    final LavaChunk[] neighbours = new LavaChunk[9]; // by chunk offset: (dz + 1) * 3 + (dx + 1)
    final long[] missingNeighbour = new long[9]; // cached absence: generation << 2 | flags (see neighbourAt)
    TerrainChunkView terrainView; // terrain the ground/waterY caches were read from
    int terrainVersion;
    long freshStamp = Long.MIN_VALUE;
    long fluxStamp = Long.MIN_VALUE;
    long neighbourStamp = Long.MIN_VALUE;
    long touchedStamp = Long.MIN_VALUE;
    int lavaCells;
    int crustCells;
    Object ocean; // LavaFlow's ocean-entry accumulator for this chunk (cache)
    long oceanGeneration = -1;

    // Per-step scratch written by this chunk's own (possibly parallel) phase and folded in sequentially.
    int touchOut; // bit per neighbour slot that received flux this step
    double maxDiffusivity; // largest flow diffusivity ρgh³/3η of a moving cell this sub-step (m²/s)
    boolean oceanInflowSeen;
    double oceanInflow;
    double oceanMaxFlux = -1;
    long oceanMaxPos;
    boolean oceanExplosive;
    double oceanHeat;
    int actionCount; // cooling actions deferred to the sequential pass (cell << 4 | kind flags)
    final int[] actions = new int[AREA];
    java.util.List<me.alex4386.typhon.engine.output.BlockChange> rendered = new java.util.ArrayList<>();

    LavaChunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        this.key = key(cx, cz);
        java.util.Arrays.fill(sourceStamp, Long.MIN_VALUE);
        java.util.Arrays.fill(bed, Double.NaN);
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    int worldX(int i) {
        return (cx << 4) | (i & 15);
    }

    int worldZ(int i) {
        return (cz << 4) | (i >> 4);
    }

    int renderCount(int i) {
        return renderMelt[i] + renderGap[i] + renderRoof[i];
    }

    void swapBuffers() {
        double[] t = thickness;
        thickness = nextThickness;
        nextThickness = t;
        t = temperature;
        temperature = nextTemperature;
        nextTemperature = t;
        t = silica;
        silica = nextSilica;
        nextSilica = t;
        t = water;
        water = nextWater;
        nextWater = t;
        int[] u = unit;
        unit = nextUnit;
        nextUnit = u;
    }

    void recount() {
        int n = 0;
        int k = 0;
        for (int i = 0; i < AREA; i++) {
            if (thickness[i] > 0) n++;
            if (crust[i] > 0) k++;
        }
        lavaCells = n;
        crustCells = k;
    }

    boolean isActive() {
        return lavaCells > 0 || crustCells > 0;
    }

    boolean hasPersistentState() {
        if (isActive()) return true;
        for (int i = 0; i < AREA; i++) {
            if (solid[i] != 0 || renderCount(i) != 0) return true;
        }
        return false;
    }
}
