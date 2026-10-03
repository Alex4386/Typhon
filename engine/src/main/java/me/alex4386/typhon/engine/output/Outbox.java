package me.alex4386.typhon.engine.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * Collects block changes and events emitted during a tick.
 *
 * <p>Multiple changes to the same position within one tick are coalesced: the final target wins and
 * the earliest {@code expected} is kept, so the host performs a single compare-and-set against the
 * state the engine originally observed.
 */
public final class Outbox {
    private final Map<Long, BlockChange> blockChanges = new LinkedHashMap<>();
    private final List<EngineEvent> events = new ArrayList<>();

    public void setBlock(BlockChange change) {
        blockChanges.merge(change.pos().pack(), change, (previous, next) -> {
            BlockId expected = previous.expected();
            return new BlockChange(previous.pos(), expected, next.to());
        });
    }

    public void emit(EngineEvent event) {
        events.add(event);
    }

    public int pendingBlockChanges() {
        return blockChanges.size();
    }

    /** Takes everything collected so far as a frame. Called by the engine at the end of a tick. */
    public EngineFrame drain(long tick) {
        EngineFrame frame = new EngineFrame(tick, new ArrayList<>(blockChanges.values()), events);
        blockChanges.clear();
        events.clear();
        return frame;
    }
}
