package me.alex4386.typhon.engine.volcano;

import me.alex4386.typhon.engine.command.EngineCommand;

/**
 * User control of vents.
 *
 * <ul>
 *   <li>{@link SealVent} plugs a vent (summit crater or fissure): magma no longer leaves through it,
 *       so the eruption continues through the remaining open vents, or ends with the chamber still
 *       pressurised if none is left. A sealed summit also keeps the chamber from failing through its
 *       roof, so pressure builds until a dike opens a new path.
 *   <li>{@link UnsealVent} reopens a sealed vent. A frozen fissure stays frozen: its feeder is solid rock.
 *   <li>{@link RemoveVent} deletes a dike-fed fissure from the volcano's vent set. The dike's intrusion
 *       stays in the stratigraphy and in the deformation field. Summit craters can be sealed, not removed.
 * </ul>
 */
public final class VentCommands {
    private VentCommands() {}

    public record SealVent(String volcanoId, String ventId) implements EngineCommand {}

    public record UnsealVent(String volcanoId, String ventId) implements EngineCommand {}

    public record RemoveVent(String volcanoId, String ventId) implements EngineCommand {}
}
