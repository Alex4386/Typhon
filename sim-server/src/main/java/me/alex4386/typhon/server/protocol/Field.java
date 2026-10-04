package me.alex4386.typhon.server.protocol;

/**
 * Tile fields of protocol v1 (docs/protocol.md §5.2) with the codec this server uses for each and
 * how often (wall seconds) it re-checks the field for changes.
 */
public enum Field {
    SURFACE_ELEVATION(1, Codecs.ELEVATION_U16_CM_DELTA, 1.0),
    LAVA_DEPTH(2, Codecs.DEPTH_U16_MM_SPARSE, 0.25),
    LAVA_TEMPERATURE(3, Codecs.TEMPERATURE_U8_LOG, 0.5),
    WATER_DEPTH(4, Codecs.F32_RAW, 2.0),
    PDC_DEPTH(5, Codecs.DEPTH_U16_MM_SPARSE, 0.25),
    LAHAR_DEPTH(6, Codecs.DEPTH_U16_MM_SPARSE, 0.5),
    ASH_DEPTH(7, Codecs.DEPTH_U16_MM_SPARSE, 2.0),
    SURFACE_TEMPERATURE(8, Codecs.TEMPERATURE_U8_LOG, 3.0),
    WATER_TABLE_DEPTH(9, Codecs.F32_RAW, 4.0),
    TOP_UNIT(10, Codecs.U16_RAW, 2.0),
    UPLIFT(11, Codecs.F32_RAW, 4.0),
    STEAM_FRACTION(12, Codecs.U8_LINEAR, 4.0);

    public final int id;
    public final int codec;
    public final double refreshSeconds;

    Field(int id, int codec, double refreshSeconds) {
        this.id = id;
        this.codec = codec;
        this.refreshSeconds = refreshSeconds;
    }

    public static Field byId(int id) {
        for (Field f : values()) if (f.id == id) return f;
        return null;
    }
}
