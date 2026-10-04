package me.alex4386.typhon.engine.worlds;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.alex4386.typhon.engine.save.SaveFormat;
import me.alex4386.typhon.engine.save.SaveStore;

/**
 * Read-only view of a save that pretends some subsystems were never saved, so a restore starts them
 * fresh (used to reset volcanoes whose definitions changed incompatibly).
 */
final class HidingSaveStore implements SaveStore {
    private final SaveStore delegate;
    private final Set<String> hiddenIds;

    HidingSaveStore(SaveStore delegate, Set<String> hiddenIds) {
        this.delegate = delegate;
        this.hiddenIds = Set.copyOf(hiddenIds);
    }

    private boolean hidden(String path) {
        for (String id : hiddenIds) {
            if (path.equals(SaveFormat.subsystemPath(id)) || path.startsWith(SaveFormat.fieldsPrefix(id))) return true;
        }
        return false;
    }

    @Override
    public byte[] read(String path) {
        if (hidden(path)) return null;
        byte[] bytes = delegate.read(path);
        if (bytes == null || !path.equals(SaveFormat.META)) return bytes;
        JsonObject meta = SaveFormat.parse(bytes);
        JsonArray kept = new JsonArray();
        for (JsonElement e : meta.getAsJsonArray("subsystems")) {
            if (!hiddenIds.contains(e.getAsJsonObject().get("id").getAsString())) kept.add(e);
        }
        meta.add("subsystems", kept);
        return SaveFormat.jsonBytes(meta);
    }

    @Override
    public boolean write(String path, byte[] data) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public void append(String path, byte[] data) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public List<String> list(String prefix) {
        List<String> result = new ArrayList<>();
        for (String path : delegate.list(prefix)) {
            if (!hidden(path)) result.add(path);
        }
        return result;
    }

    @Override
    public void delete(String path) {
        throw new UnsupportedOperationException("read-only");
    }
}
