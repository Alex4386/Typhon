package me.alex4386.typhon.engine.dike;

import me.alex4386.typhon.engine.command.EngineCommand;

public final class DikeCommands {
    private DikeCommands() {}

    /** Manual override: nucleate a dike from the chamber of {@code volcanoId} at its next step. */
    public record ForceDike(String volcanoId) implements EngineCommand {}
}
