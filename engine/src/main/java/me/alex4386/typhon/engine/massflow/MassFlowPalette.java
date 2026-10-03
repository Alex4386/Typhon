package me.alex4386.typhon.engine.massflow;

import me.alex4386.typhon.engine.world.BlockState;

/**
 * Blocks for mass-flow deposits.
 *
 * <ul>
 *   <li>Ignimbrite: non-welded → {@code tuff}; laid down hotter than the welding temperature (dense,
 *       glassy welded tuff near the vent) → {@code blackstone}. Thin PDC ash veneer →
 *       {@code light_gray_concrete_powder}.
 *   <li>Lahar deposits: fast, coarse bedload → {@code gravel}; slow, fine → {@code mud}, compacting to
 *       {@code packed_mud} when buried. Thin veneers → {@code coarse_dirt}, then {@code mud}.
 * </ul>
 */
public final class MassFlowPalette {
    public static final BlockState TUFF = BlockState.minecraft("tuff");
    public static final BlockState WELDED_TUFF = BlockState.minecraft("blackstone");
    public static final BlockState PDC_VENEER = BlockState.minecraft("light_gray_concrete_powder");

    public static final BlockState GRAVEL = BlockState.minecraft("gravel");
    public static final BlockState MUD = BlockState.minecraft("mud");
    public static final BlockState PACKED_MUD = BlockState.minecraft("packed_mud");
    public static final BlockState LAHAR_THIN_VENEER = BlockState.minecraft("coarse_dirt");
    public static final BlockState LAHAR_VENEER = MUD;

    public static final BlockState AIR = BlockState.minecraft("air");
    public static final BlockState WATER = BlockState.minecraft("water");

    private MassFlowPalette() {}

    static BlockState ignimbrite(double depositTemperatureC, double weldingTemperatureC) {
        return depositTemperatureC >= weldingTemperatureC ? WELDED_TUFF : TUFF;
    }

    static BlockState laharBlock(double depositSpeed, double coarseSpeed) {
        return depositSpeed >= coarseSpeed ? GRAVEL : MUD;
    }
}
