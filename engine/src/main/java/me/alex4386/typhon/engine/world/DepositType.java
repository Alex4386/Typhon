package me.alex4386.typhon.engine.world;

/** How a stratigraphic unit was emplaced. */
public enum DepositType {
    /** Not attributed (engine edits without provenance, host-placed material). */
    FILL,
    BASEMENT,
    EDIFICE,
    LAVA,
    TUBE_ROOF,
    PDC,
    FALL,
    LAHAR,
    HYALOCLASTITE,
    INTRUSION,
    /** A cavity (lava tube, tunnel, collapse pit); the unit of a VOID layer. */
    CAVITY,
    /** Talus, rockfall and slump debris moved a short way downslope by slope failure. */
    LANDSLIDE,
    /** Deposit of a debris avalanche (long-runout rock/debris flow from a large slope failure). */
    DEBRIS_AVALANCHE,
    /** Breccia thrown out of an explosion crater (ejecta blanket and rim). */
    EJECTA,
    /** Hydrothermal precipitates and alteration products: siliceous sinter, native sulfur, acid-altered clay. */
    HYDROTHERMAL
}
