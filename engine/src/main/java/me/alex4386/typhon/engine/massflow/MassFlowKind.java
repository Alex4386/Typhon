package me.alex4386.typhon.engine.massflow;

/** The gravity-driven volcanic mass flows modelled by this package. */
public enum MassFlowKind {
    /** Pyroclastic density current: hot gas–particle mixture (temperature tracked). */
    PDC,
    /** Lahar: water–sediment mixture (sediment concentration tracked). */
    LAHAR,
    /** Debris avalanche: dry granular rock debris released by a large slope failure. */
    DEBRIS_AVALANCHE
}
