package me.alex4386.typhon.engine.save;

import com.google.gson.JsonObject;

/**
 * Sink for one subsystem's persistent state.
 *
 * <ul>
 *   <li>{@link #json()}: small, 0-D and transient state (scalars, lists of in-flight objects).
 *   <li>{@link #field}: large spatial arrays, stored per chunk in compressed region files so that
 *       saves stay small and only changed regions are rewritten.
 * </ul>
 */
public interface StateWriter {
    /** JSON object for scalar state. */
    JsonObject json();

    /**
     * Opens spatial field {@code name}. Bump {@code schemaVersion} whenever the arrays a chunk holds
     * change meaning, so loaders can migrate or reject old saves.
     */
    Field field(String name, int schemaVersion);

    interface Field {
        /**
         * Stores one chunk. Chunk coordinates are free-form keys (e.g. 16-block chunks for column
         * grids, or {@code (0, 0)} for a single fixed grid); region files group them 32×32.
         */
        void put(int chunkX, int chunkZ, FieldChunk chunk);
    }
}
