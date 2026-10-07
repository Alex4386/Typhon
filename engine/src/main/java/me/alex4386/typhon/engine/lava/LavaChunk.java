package me.alex4386.typhon.engine.lava;


/** Lava state of one 16×16 chunk as flat primitive arrays, indexed {@code (z & 15) * 16 + (x & 15)}. */
final class LavaChunk {
    static final int AREA = 256;
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
    final double[] crust = new double[AREA]; // rigid crust/roof on top of the melt (real m)
    final double[] roofTop = new double[AREA]; // elevation of the crust top (m)
    final byte[] crustKind = new byte[AREA]; // LavaRocks.crustKind of the crust
    int[] unit = new int[AREA]; // stratigraphic unit (eruption) of the melt; kept after it drains (roofs, films)
    /** Time (s) the column's bed has been under this lava: the age of the conductive boundary layer below it. */
    double[] contact = new double[AREA];

    // Double buffers and per-step scratch (transient)
    double[] nextThickness = new double[AREA];
    double[] nextTemperature = new double[AREA];
    double[] nextSilica = new double[AREA];
    double[] nextWater = new double[AREA];
    int[] nextUnit = new int[AREA];
    double[] nextContact = new double[AREA];
    final double[] outflow = new double[DIRECTIONS * AREA]; // direction-major, as thickness (m)
    final double[] speed = new double[AREA]; // physical mean flow speed q/h this step (m/s)
    /** Standing water surface elevation (m) from the world model, NaN where dry (cached with the bed). */
    final double[] waterLevel = new double[AREA];
    /** Standing water (m) on each column from the ground model, refreshed at the start of a step (transient). */
    final float[] surfaceWater = new float[AREA];
    boolean surfaceWaterSet; // surfaceWater holds values from a refresh (cleared when the chunk goes quiet)
    /**
     * Heat (J) this step's cooling sent into standing water per column, and into the ground per
     * ground-model heat cell within the chunk (indexed as LavaFlow lays them out; transient).
     */
    final double[] boilHeat = new double[AREA];
    final double[] groundHeat = new double[AREA];
    /** World-model ground surface + uplift (m), NaN where unknown: the bed the lava flows on. */
    final double[] bed = new double[AREA];
    long bedVersion = Long.MIN_VALUE; // world version sum the bed cache was read at
    long seenEdits = Long.MIN_VALUE; // world edit counter at the last bed check (transient)
    final long[] sourceStamp = new long[AREA]; // == step stamp while an effusive source feeds the cell
    final LavaChunk[] neighbours = new LavaChunk[9]; // by chunk offset: (dz + 1) * 3 + (dx + 1)
    final long[] missingNeighbour = new long[9]; // cached absence: generation << 2 | flags (see neighbourAt)
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
    long oceanMaxPos; // packed column of the strongest inflow
    boolean oceanExplosive;
    double oceanHeat;
    int actionCount; // cooling actions deferred to the sequential pass (cell << 4 | kind flags)
    final int[] actions = new int[AREA];

    LavaChunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        this.key = key(cx, cz);
        java.util.Arrays.fill(sourceStamp, Long.MIN_VALUE);
        java.util.Arrays.fill(bed, Double.NaN);
        java.util.Arrays.fill(waterLevel, Double.NaN);
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
        t = contact;
        contact = nextContact;
        nextContact = t;
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
        return isActive();
    }
}
