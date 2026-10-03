package me.alex4386.typhon.engine.geothermal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;

/**
 * Resolves the blocks a subsystem would like to place against what the host can actually place.
 *
 * <p>Hosts construct a palette from the set of block ids their registry supports (older game
 * versions lack e.g. {@code minecraft:sulfur}). Each preferred id can have an ordered fallback chain;
 * {@link #resolve} returns the first supported entry, or {@code null} if nothing in the chain is
 * supported, in which case the caller skips that placement.
 *
 * <p>Fallbacks either drop the preferred state's properties (the default, for unrelated blocks) or
 * keep them ({@link #compatibleFallback}, for blocks sharing the same property set, such as
 * {@code sulfur_spike} → {@code pointed_dripstone}).
 *
 * <p>Generic on purpose so it can move to a shared package once other subsystems need it.
 */
public final class BlockPalette {
    /** One step of a fallback chain. */
    public record Fallback(BlockId id, boolean keepProperties) {}

    private final Predicate<BlockId> supported;
    private final Map<BlockId, List<Fallback>> chains = new HashMap<>();

    private BlockPalette(Predicate<BlockId> supported) {
        this.supported = supported;
    }

    /** Palette for a host that supports every id (fallbacks are never used). */
    public static BlockPalette unrestricted() {
        return new BlockPalette(id -> true);
    }

    /** Palette for a host whose registry contains exactly {@code ids}. */
    public static BlockPalette supporting(Collection<BlockId> ids) {
        Set<BlockId> copy = Set.copyOf(ids);
        return new BlockPalette(copy::contains);
    }

    /** Appends property-dropping fallbacks for {@code preferred}, tried in order. */
    public BlockPalette fallback(BlockId preferred, BlockId... alternatives) {
        List<Fallback> chain = chains.computeIfAbsent(preferred, k -> new ArrayList<>());
        for (BlockId alternative : alternatives) chain.add(new Fallback(alternative, false));
        return this;
    }

    /** Appends a fallback that shares {@code preferred}'s block-state properties. */
    public BlockPalette compatibleFallback(BlockId preferred, BlockId alternative) {
        chains.computeIfAbsent(preferred, k -> new ArrayList<>()).add(new Fallback(alternative, true));
        return this;
    }

    public boolean supports(BlockId id) {
        return supported.test(id);
    }

    /** First supported id in {@code preferred}'s chain, or {@code null}. */
    public BlockId resolve(BlockId preferred) {
        if (supports(preferred)) return preferred;
        for (Fallback fallback : chains.getOrDefault(preferred, List.of())) {
            if (supports(fallback.id())) return fallback.id();
        }
        return null;
    }

    /** First supported state in {@code preferred}'s chain, or {@code null}. */
    public BlockState resolve(BlockState preferred) {
        if (supports(preferred.id())) return preferred;
        for (Fallback fallback : chains.getOrDefault(preferred.id(), List.of())) {
            if (supports(fallback.id())) {
                return fallback.keepProperties()
                        ? new BlockState(fallback.id(), preferred.properties())
                        : BlockState.of(fallback.id());
            }
        }
        return null;
    }
}
