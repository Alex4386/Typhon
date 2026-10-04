package me.alex4386.typhon.engine.save;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link SaveStore} backed by a directory. Writes go to a temporary file that is atomically moved
 * into place, and are skipped when the file already holds the same bytes.
 */
public final class DirectorySaveStore implements SaveStore {
    private final Path root;
    private long writes;

    public DirectorySaveStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    private Path resolve(String path) {
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Path escapes the save directory: " + path);
        }
        return resolved;
    }

    @Override
    public byte[] read(String path) {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean write(String path, byte[] data) {
        Path file = resolve(path);
        try {
            if (Files.isRegularFile(file) && Files.size(file) == data.length
                    && Arrays.equals(Files.readAllBytes(file), data)) {
                return false;
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            writes++;
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void append(String path, byte[] data) {
        Path file = resolve(path);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> p.startsWith(prefix) && !p.endsWith(".tmp"))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Number of writes that actually changed a file (for incremental-save checks). */
    public long writeCount() {
        return writes;
    }
}
