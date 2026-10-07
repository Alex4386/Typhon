package me.alex4386.typhon.engine.terrain;

import java.util.List;
import me.alex4386.typhon.engine.command.EngineCommand;

/**
 * Ground the host hands to the engine: columns not known yet are built from the world's geology up to
 * their surface; known columns are brought to the given surface (deposit or erosion of unattributed
 * material) and water level.
 */
public record GroundImport(List<GroundColumn> columns) implements EngineCommand {
    public GroundImport {
        columns = List.copyOf(columns);
    }
}
