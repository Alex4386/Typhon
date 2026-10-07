package me.alex4386.typhon.engine.geothermal;

/** Kinds of surface manifestation the geothermal subsystem creates. */
public enum HydrothermalFeature {
    /** Steam/gas vent; announced every step via {@link FumaroleActivity}. */
    FUMAROLE,
    /** Geyser: a 1–4 m flooded vent pit fed by boiling groundwater, with a sinter apron. */
    GEYSER,
    /** Hot pool rimmed with sinter. */
    HOT_SPRING,
    /** Acid hot pool floored and rimmed with native sulfur. */
    SULFUR_SPRING,
    MUD_POT,
    /** Seafloor hydrothermal vent, with sulfur precipitated around it. */
    SUBMARINE_VENT,
    /** Native sulfur crust that builds up in stages around fumaroles. */
    SULFUR_DEPOSIT,
    /** Acid-sulfate alteration of the top rock to clay. */
    ACID_ALTERATION,
    /** Silica sinter / travertine. */
    SINTER,
    /** Low-temperature epithermal mercury mineralization. */
    CINNABAR
}
