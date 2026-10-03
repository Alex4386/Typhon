package me.alex4386.typhon.engine.geothermal;

/** Kinds of surface manifestation the geothermal subsystem creates. */
public enum HydrothermalFeature {
    /** Steam/gas vent; announced every step via {@link FumaroleActivity}. */
    FUMAROLE,
    /** Vanilla geyser structure (magma block, potent sulfur, 1–4 water). */
    GEYSER,
    /** Hot pool rimmed with sinter. */
    HOT_SPRING,
    /** Acid pool over potent sulfur, rimmed with sulfur. */
    SULFUR_SPRING,
    MUD_POT,
    /** Seafloor vent (magma block, which also makes a vanilla bubble column). */
    SUBMARINE_VENT,
    /** Native sulfur crust, optionally with spikes on top. */
    SULFUR_DEPOSIT,
    /** Acid-sulfate alteration to clay / terracotta. */
    ACID_ALTERATION,
    /** Silica sinter / travertine. */
    SINTER,
    /** Low-temperature epithermal mercury mineralization. */
    CINNABAR
}
