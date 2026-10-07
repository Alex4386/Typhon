package me.alex4386.typhon.mc;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Platform-neutral block state: a {@link BlockId} plus string properties, written like Minecraft's
 * block-state syntax, e.g. {@code minecraft:basalt[axis=x]} or {@code minecraft:lava[level=3]}.
 *
 * <p>Properties are kept sorted so equal states have equal string forms. Hosts ignore properties
 * their block does not support.
 */
public record BlockState(BlockId id, Map<String, String> properties) {
    public static final BlockState AIR = of(BlockId.AIR);

    public BlockState {
        Objects.requireNonNull(id, "id");
        properties = Collections.unmodifiableMap(new TreeMap<>(properties));
    }

    public static BlockState of(BlockId id) {
        return new BlockState(id, Map.of());
    }

    public static BlockState minecraft(String path) {
        return of(BlockId.minecraft(path));
    }

    public BlockState with(String key, String value) {
        TreeMap<String, String> next = new TreeMap<>(properties);
        next.put(key, value);
        return new BlockState(id, next);
    }

    public BlockState with(String key, int value) {
        return with(key, Integer.toString(value));
    }

    public String property(String key) {
        return properties.get(key);
    }

    /** Parses {@code namespace:path[key=value,...]}. */
    public static BlockState parse(String text) {
        int bracket = text.indexOf('[');
        if (bracket < 0) return of(BlockId.parse(text));
        if (!text.endsWith("]")) throw new IllegalArgumentException("Unterminated properties: " + text);
        BlockId id = BlockId.parse(text.substring(0, bracket));
        TreeMap<String, String> props = new TreeMap<>();
        String body = text.substring(bracket + 1, text.length() - 1);
        if (!body.isEmpty()) {
            for (String pair : body.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("Invalid property: " + pair);
                props.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return new BlockState(id, props);
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) return id.toString();
        StringBuilder sb = new StringBuilder(id.toString()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
