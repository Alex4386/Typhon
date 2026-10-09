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
     * A conduit still molten under a solidified cap that holds the rock's tensile strength {@code tensileMPa}
     * (a chamber of that strength): a quarter of its radius frozen, so the cap ({@code 2·C·δ/r} with the
     * Griffith cohesion {@code C = 2 T}) gives way, and the chamber erupts through its crater, once the
     * overpressure reaches that strength.
     */
    public static ConduitConfig molten(double tensileMPa) {
        return ConduitConfig.DEFAULT.withInitialOpenness(0.75);
    }

    /** {@link #molten(double)} for the default chamber's strength. */
    public static ConduitConfig molten() {
        return molten(DEFAULT_TENSILE_MPA);
    }

    /**
     * The capped conduit of {@link #molten(double)} over gas-rich magma: the cap's failure at the tensile
     * strength decompresses the vesicular magma suddenly enough to fragment it (as a sealed silicic system
     * does); whether it does follows from the magma, not the conduit.
     */
    public static ConduitConfig plugged(double tensileMPa) {
        return molten(tensileMPa);
    }
}
