package me.alex4386.typhon.engine.save;

import com.google.gson.JsonObject;
import java.util.List;

/** Source of one subsystem's persistent state, mirroring {@link StateWriter}. */
public interface StateReader {
    /** JSON object written through {@link StateWriter#json()}. */
    JsonObject json();

    /** Spatial field {@code name}, or {@code null} if the save does not contain it. */
    Field field(String name);

    interface Field {
        int schemaVersion();

        /** Every stored chunk, sorted by (chunkZ, chunkX). */
        List<Entry> chunks();

        /** One chunk, or {@code null}. */
        FieldChunk get(int chunkX, int chunkZ);
    }

    record Entry(int chunkX, int chunkZ, FieldChunk data) {}
}
