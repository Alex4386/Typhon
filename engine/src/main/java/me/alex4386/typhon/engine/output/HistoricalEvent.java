package me.alex4386.typhon.engine.output;

/**
 * Marks events that are part of a volcano's permanent record (eruptions, the earthquake catalogue,
 * alert changes, new vents). They cannot be recomputed from state, so the engine appends them to
 * the save's history log ({@code log/events.ndjson}).
 */
public interface HistoricalEvent extends EngineEvent {
    /**
     * Volcano the event belongs to, or {@code null} for world-level events. Records with a
     * {@code String volcanoId} component implement this automatically; it routes per-volcano history.
     */
    default String volcanoId() {
        return null;
    }
}
