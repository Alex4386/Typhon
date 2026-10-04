package me.alex4386.typhon.engine.worlds;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.alex4386.typhon.engine.alert.AlertEvents;
import me.alex4386.typhon.engine.magma.MagmaEvents;
import me.alex4386.typhon.engine.output.HistoricalEvent;
import me.alex4386.typhon.engine.save.SaveFormat;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.seismic.SeismicEvent;

/**
 * Splits the engine's historical events into per-volcano logs:
 * {@code history/volcanoes/<id>/{eruptions,seismic,alerts,events}.ndjson} and
 * {@code history/world.ndjson} for events that belong to no volcano.
 */
public final class HistoryRouter {
    private final SaveStore store;
    private final Set<String> volcanoIds;

    public HistoryRouter(SaveStore store, Set<String> volcanoIds) {
        this.store = store;
        this.volcanoIds = Set.copyOf(volcanoIds);
    }

    public void route(List<HistoricalEvent> events) {
        Map<String, StringBuilder> files = new LinkedHashMap<>();
        for (HistoricalEvent event : events) {
            files.computeIfAbsent(pathFor(event), k -> new StringBuilder()).append(SaveFormat.historyLine(event)).append('\n');
        }
        for (Map.Entry<String, StringBuilder> e : files.entrySet()) {
            store.append(e.getKey(), e.getValue().toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    String pathFor(HistoricalEvent event) {
        String volcano = volcanoOf(event);
        if (volcano == null) return WorldDirectory.HISTORY + "/world.ndjson";
        return WorldDirectory.HISTORY + "/volcanoes/" + volcano + "/" + category(event) + ".ndjson";
    }

    /** {@link HistoricalEvent#volcanoId()}, else a {@code source}/{@code target} id of the form {@code prefix:<volcano>}. */
    String volcanoOf(HistoricalEvent event) {
        String id = event.volcanoId();
        if (id != null) return id;
        if (event.getClass().isRecord()) {
            for (RecordComponent c : event.getClass().getRecordComponents()) {
                if (c.getType() != String.class) continue;
                if (!c.getName().equals("source") && !c.getName().equals("target") && !c.getName().equals("id")) continue;
                try {
                    String value = (String) c.getAccessor().invoke(event);
                    if (value == null) continue;
                    int colon = value.lastIndexOf(':');
                    String candidate = colon >= 0 ? value.substring(colon + 1) : value;
                    if (volcanoIds.contains(candidate)) return candidate;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return null;
    }

    static String category(HistoricalEvent event) {
        if (event instanceof SeismicEvent) return "seismic";
        if (event instanceof AlertEvents.AlertLevelChanged) return "alerts";
        if (event.getClass().getEnclosingClass() == MagmaEvents.class) return "eruptions";
        return "events";
    }
}
