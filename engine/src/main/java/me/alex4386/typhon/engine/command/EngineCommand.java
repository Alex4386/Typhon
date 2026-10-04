package me.alex4386.typhon.engine.command;

/**
 * Marker for inputs sent to the engine (start eruption, terrain snapshot, world changed, ...).
 *
 * <p>Commands are plain data so they can cross a thread or process boundary unchanged. They are
 * queued by {@code Engine.submit} and applied at the start of the next step.
 */
public interface EngineCommand {}
