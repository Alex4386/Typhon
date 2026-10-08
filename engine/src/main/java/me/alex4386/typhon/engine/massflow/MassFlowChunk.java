package me.alex4386.typhon.engine.massflow;

/** Mass-flow state of one 16×16 chunk as flat primitive arrays, indexed {@code (z & 15) * 16 + (x & 15)}. */
final class MassFlowChunk {
    static final int AREA = 256;

    final int cx;
    final int cz;
    final long key;

    // Simulation state (persisted)
    double[] depth = new double[AREA];        // flow depth h (m)
    double[] vx = new double[AREA];           // depth-averaged velocity (m/s)
    double[] vz = new double[AREA];
    double[] temperature = new double[AREA];  // °C (PDC)
    double[] sediment = new double[AREA];     // sediment volume fraction (lahar)
    final double[] depositTotal = new double[AREA]; // all deposit laid down in the column (m)
    final double[] worldPending = new double[AREA];     // deposit not yet written to the world model (m)
    final double[] worldPendingHeat = new double[AREA]; // Σ thickness·temperature of that pool
    final double[] soak = new double[AREA];         // rain soaked into the loose material (m of water)

    // Double buffers and per-substep scratch (transient)
    double[] nextDepth = new double[AREA];
    double[] nextVx = new double[AREA];
    double[] nextVz = new double[AREA];
    double[] nextTemperature = new double[AREA];
    double[] nextSediment = new double[AREA];
    final double[] outVolume = new double[4 * AREA];  // direction-major volume leaving each cell (m³)
    final double[] outSpeed = new double[4 * AREA];   // pipe speed in that direction after forcing (m/s)
    /** World-model ground surface (m, uplift included), NaN where unknown; refreshed every step. */
    final double[] bed = new double[AREA];
    /** Standing water surface elevation (m), NaN where dry. */
    final double[] waterLevel = new double[AREA];
    final MassFlowChunk[] neighbours = new MassFlowChunk[4];
    long freshStamp = Long.MIN_VALUE;
    long fluxStamp = Long.MIN_VALUE;
    long neighbourStamp = Long.MIN_VALUE;
    long touchedStamp = Long.MIN_VALUE;
    int touchOut; // per-step scratch: neighbour directions that received flow (folded sequentially)
    double lostResidue; // per-step scratch: rounding residue dropped by gather (folded sequentially)
    long depositStamp = Long.MIN_VALUE;
    int flowCells;
    int soakedCells;

    MassFlowChunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        this.key = key(cx, cz);
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
        double[] t = depth;
        depth = nextDepth;
        nextDepth = t;
        t = vx;
        vx = nextVx;
        nextVx = t;
        t = vz;
        vz = nextVz;
        nextVz = t;
        t = temperature;
        temperature = nextTemperature;
        nextTemperature = t;
        t = sediment;
        sediment = nextSediment;
        nextSediment = t;
    }

    void recount() {
        int n = 0;
        int e = 0;
        for (int i = 0; i < AREA; i++) {
            if (depth[i] > 0) n++;
            if (soak[i] > 0) e++;
        }
        flowCells = n;
        soakedCells = e;
    }

    boolean hasPersistentState() {
        if (flowCells > 0 || soakedCells > 0) return true;
        for (int i = 0; i < AREA; i++) {
            if (depositTotal[i] != 0 || worldPending[i] != 0) return true;
        }
        return false;
    }
}
