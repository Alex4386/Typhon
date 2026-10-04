package me.alex4386.typhon.engine.save;

import java.util.List;

/**
 * Where a save lives: a flat namespace of {@code /}-separated relative paths holding bytes. See
 * {@link DirectorySaveStore} (on disk) and {@link InMemorySaveStore} (tests, state hashing).
 *
 * <p>I/O failures surface as {@link java.io.UncheckedIOException}.
 */
public interface SaveStore {
    /** Contents of {@code path}, or {@code null} if it does not exist. */
    byte[] read(String path);

    /**
     * Replaces {@code path} with {@code data}. Stores skip the write when the content is unchanged,
     * which makes saves incremental: only regions that actually changed are rewritten.
     *
     * @return whether anything was written
     */
    boolean write(String path, byte[] data);

    /** Appends {@code data} to {@code path}, creating it if needed (append-only logs). */
    void append(String path, byte[] data);

    /** All paths starting with {@code prefix}, sorted. */
    List<String> list(String prefix);

    void delete(String path);
}
