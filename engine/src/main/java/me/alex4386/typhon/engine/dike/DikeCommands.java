package me.alex4386.typhon.engine.dike;

import me.alex4386.typhon.engine.command.EngineCommand;

public final class DikeCommands {
    private DikeCommands() {}

    /** Manual override: nucleate a dike from the chamber of {@code volcanoId} at its next step. */
    public record ForceDike(String volcanoId) implements EngineCommand {}

    /**
     * Stops a propagating dike where it is (as if it had stalled): it freezes into an intrusion and
     * never reaches the surface. Ignored for a dike that is not propagating.
     */
    public record ArrestDike(String volcanoId, int dikeId) implements EngineCommand {}

    /**
     * Deletes a dike: a propagating one is arrested first; a fissure it opened stops being a vent of
     * the volcano. The intrusion stays in the rock and in the deformation field.
     */
    public record RemoveDike(String volcanoId, int dikeId) implements EngineCommand {}

    /**
     * Blocks (or allows again) the nucleation of dikes at wall rupture from the chamber of
     * {@code volcanoId}. Forced dikes ({@link ForceDike}) still start. Overpressure keeps building, so a
     * blocked volcano with a sealed summit stays pressurised.
     */
    public record BlockDikes(String volcanoId, boolean blocked) implements EngineCommand {}
}
