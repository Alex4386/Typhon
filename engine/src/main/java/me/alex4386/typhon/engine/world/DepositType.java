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
    CAVITY
}
