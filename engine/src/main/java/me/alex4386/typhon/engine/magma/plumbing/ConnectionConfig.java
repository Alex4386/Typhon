package me.alex4386.typhon.engine.magma.plumbing;

import java.util.Objects;

/**
 * A magma pathway between two chambers of a volcano's plumbing: a cylindrical conduit or a dike
 * (slot). Magma flows from {@code from} to {@code to} when the pressure difference beats the
 * magmastatic head between them (see {@link MagmaTransfer}).
 *
 * @param id unique within the volcano
 * @param from source chamber id ({@code main} is the eruptive chamber)
 * @param to receiving chamber id
 * @param kind pipe (conduit) or slot (dike)
 * @param radiusM conduit radius (m); used when {@code kind} is a conduit
 * @param widthM dike opening (m); used when {@code kind} is a dike
 * @param strikeLengthM dike length along strike (m); used when {@code kind} is a dike
 * @param lengthM path length (m); NaN = the distance between the chamber centres
 * @param open closed connections carry nothing (a dial: seal or open the pathway)
 * @param freezeOnStall whether a pathway whose flow stays below {@code stallRateM3PerS} for
 *     {@code freezeSeconds} of physical time freezes shut (magma solidifying in a stagnant conduit)
 * @param stallRateM3PerS flow below which the pathway counts as stalled (m³/s); NaN = computed, the flow too
 *     slow to keep it from freezing (see {@link MagmaTransfer#stallRateM3PerS})
 * @param freezeSeconds physical seconds of stall before it freezes; NaN = computed, the conductive
 *     solidification time across its half-width (see {@link MagmaTransfer#freezeSeconds})
 */
public record ConnectionConfig(String id, String from, String to, Kind kind, double radiusM, double widthM, double strikeLengthM,
        double lengthM, boolean open, boolean freezeOnStall, double stallRateM3PerS, double freezeSeconds) {

    public enum Kind {
        CONDUIT,
        DIKE
    }

    public ConnectionConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
        if (from.equals(to)) throw new IllegalArgumentException("connection " + id + " joins chamber " + from + " to itself");
        if (!(radiusM > 0)) throw new IllegalArgumentException("radiusM must be > 0");
        if (!(widthM > 0)) throw new IllegalArgumentException("widthM must be > 0");
        if (!(strikeLengthM > 0)) throw new IllegalArgumentException("strikeLengthM must be > 0");
        if (!Double.isNaN(lengthM) && !(lengthM > 0)) throw new IllegalArgumentException("lengthM must be > 0 (or NaN = computed)");
        if (!Double.isNaN(stallRateM3PerS) && !(stallRateM3PerS >= 0)) {
            throw new IllegalArgumentException("stallRateM3PerS must be >= 0 (or NaN = computed)");
        }
        if (!Double.isNaN(freezeSeconds) && !(freezeSeconds > 0)) {
            throw new IllegalArgumentException("freezeSeconds must be > 0 (or NaN = computed)");
        }
    }

    /**
     * Defaults: a 1.5 m conduit (basaltic feeders are metres across; Wilson &amp; Head 1981), or a 1 m dike
     * 500 m long (Rubin 1995); freezing off, its stall rate and freezing time computed from the pathway.
     */
    public static ConnectionConfig of(String id, String from, String to, Kind kind) {
        return new ConnectionConfig(id, from, to, kind, 1.5, 1.0, 500, Double.NaN, true, false, Double.NaN, Double.NaN);
    }

    public ConnectionConfig withOpen(boolean v) {
        return new ConnectionConfig(id, from, to, kind, radiusM, widthM, strikeLengthM, lengthM, v, freezeOnStall, stallRateM3PerS,
                freezeSeconds);
    }
}
