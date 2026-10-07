package me.alex4386.typhon.engine.testing;

import me.alex4386.typhon.engine.magma.ConduitConfig;

/**
 * Conduits for tests of volcanoes that already have one. A chamber's default is none: its magma reaches the
 * surface only through a dike.
 */
public final class TestConduits {
    private TestConduits() {}

    /** The default chamber's tensile strength (MPa). */
    public static final double DEFAULT_TENSILE_MPA = 15;

    /**
     * A conduit that is still molten, capped at the rock's tensile strength {@code tensileMPa}: the chamber
     * erupts through its crater once its overpressure reaches that strength.
     */
    public static ConduitConfig molten(double tensileMPa) {
        return ConduitConfig.DEFAULT.withInitialOpenness(1).withReopenOverpressureMPa(tensileMPa);
    }

    /** {@link #molten(double)} for the default chamber's strength. */
    public static ConduitConfig molten() {
        return molten(DEFAULT_TENSILE_MPA);
    }

    /**
     * A conduit mostly solidified since its last eruption (a quarter of its radius still molten), plugged by
     * its solid rim: it fails at the rock's tensile strength {@code tensileMPa}, and the column decompresses
     * suddenly (as a sealed silicic system does).
     */
    public static ConduitConfig plugged(double tensileMPa) {
        return ConduitConfig.DEFAULT.withInitialOpenness(0.25).withReopenOverpressureMPa(tensileMPa);
    }
}
