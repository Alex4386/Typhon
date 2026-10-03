package me.alex4386.typhon.engine.lava;

/** Lava state of one 16×16 chunk as flat primitive arrays, indexed {@code (z & 15) * 16 + (x & 15)}. */
final class LavaChunk {
    static final int AREA = 256;
    static final int UNKNOWN = Integer.MIN_VALUE;

    final int cx;
    final int cz;
    final long key;

    // Simulation state (persisted)
    double[] thickness = new double[AREA];
    double[] temperature = new double[AREA];
    double[] silica = new double[AREA];
    double[] water = new double[AREA];
    final double[] solid = new double[AREA]; // solidified rock not yet amounting to a whole block
    final int[] renderBottom = new int[AREA];
    final short[] renderCount = new short[AREA];
    final byte[] renderTop = new byte[AREA]; // kind << 3 | level; kind 0 none, 1 lava, 2 magma crust

    // Double buffers and per-step scratch (transient)
    double[] nextThickness = new double[AREA];
    double[] nextTemperature = new double[AREA];
    double[] nextSilica = new double[AREA];
    double[] nextWater = new double[AREA];
    final double[] outflow = new double[4 * AREA]; // direction-major
    final int[] ground = new int[AREA];
    final int[] waterY = new int[AREA];
    final LavaChunk[] neighbours = new LavaChunk[4];
    long freshStamp = Long.MIN_VALUE;
    long fluxStamp = Long.MIN_VALUE;
    long neighbourStamp = Long.MIN_VALUE;
    long touchedStamp = Long.MIN_VALUE;
    int lavaCells;

    LavaChunk(int cx, int cz) {
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
    }

    void recount() {
        int n = 0;
        for (int i = 0; i < AREA; i++) if (thickness[i] > 0) n++;
        lavaCells = n;
    }

    boolean hasPersistentState() {
        if (lavaCells > 0) return true;
        for (int i = 0; i < AREA; i++) {
            if (solid[i] != 0 || renderCount[i] != 0) return true;
        }
        return false;
    }
}
