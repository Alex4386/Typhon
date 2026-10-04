package me.alex4386.typhon.engine.world;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Platform-neutral block identifier in namespaced form, e.g. {@code minecraft:basalt}.
 *
 * <p>The engine never references platform block types. Hosts resolve these ids against their own
 * registries (Bukkit {@code Material}, Fabric {@code Registries.BLOCK}, ...).
 */
public record BlockId(String namespace, String path) {
    /** Orders ids by their {@code namespace:path} text. */
    public static final java.util.Comparator<BlockId> BY_NAME = java.util.Comparator.comparing(BlockId::toString);

    /**
     * A mutable set with deterministic (sorted) iteration — use it for block sets in configuration
     * objects, whose serialised form is hashed.
     */
    public static java.util.SortedSet<BlockId> sortedSet(java.util.Collection<BlockId> ids) {
        java.util.TreeSet<BlockId> set = new java.util.TreeSet<>(BY_NAME);
        set.addAll(ids);
        return set;
    }

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    public static final String MINECRAFT = "minecraft";

    public static final BlockId AIR = minecraft("air");

    public BlockId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    public static BlockId minecraft(String path) {
        return new BlockId(MINECRAFT, path);
    }

    /** Parses {@code namespace:path}; a bare path defaults to the {@code minecraft} namespace. */
    public static BlockId parse(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return minecraft(id);
        return new BlockId(id.substring(0, colon), id.substring(colon + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
