package me.alex4386.typhon.engine.alert;

/**
 * Named eruption styles, as an observer would call what a volcano is doing. Estimated from the
 * simulated eruption ({@link EruptionClassifier}); never configured and never fed back into it.
 */
public enum EruptionStyle {
    /** Fluid lava fountains and flows. */
    HAWAIIAN,
    /** Discrete bursts of gas slugs throwing out bombs and spatter. */
    STROMBOLIAN,
    /** Discrete cannon-like explosions of a stiff, viscous plug. */
    VULCANIAN,
    /** Collapsing column or dome feeding pyroclastic density currents from a low eruption. */
    PELEAN,
    /** Sustained column above about 20 km. */
    PLINIAN,
    /** Slow extrusion of viscous lava piling up over the vent. */
    LAVA_DOME,
    /** Sustained column of about 10–20 km. */
    SUBPLINIAN,
    /** Magma–water explosions: cock's-tail jets, wet ash, tuff rings. */
    SURTSEYAN,
    /** Steam explosions driven by heated ground water without fresh magma. */
    PHREATIC,
    /** No single style dominates. */
    MIXED
}
