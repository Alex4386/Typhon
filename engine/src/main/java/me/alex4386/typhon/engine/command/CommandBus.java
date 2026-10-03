package me.alex4386.typhon.engine.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dispatches commands to every handler registered for their exact type, in registration order.
 *
 * <p>Several subsystems may handle the same command type (e.g. one tephra subsystem per volcano);
 * commands aimed at one of them carry a target id and the other handlers ignore them.
 */
public final class CommandBus {
    private final Map<Class<?>, List<Consumer<? super EngineCommand>>> handlers = new HashMap<>();

    public <T extends EngineCommand> void register(Class<T> type, Consumer<? super T> handler) {
        handlers.computeIfAbsent(type, k -> new ArrayList<>()).add(command -> handler.accept(type.cast(command)));
    }

    public void dispatch(EngineCommand command) {
        List<Consumer<? super EngineCommand>> list = handlers.get(command.getClass());
        if (list == null) {
            throw new UnhandledCommandException(command);
        }
        for (Consumer<? super EngineCommand> handler : list) {
            handler.accept(command);
        }
    }

    public static final class UnhandledCommandException extends RuntimeException {
        public UnhandledCommandException(EngineCommand command) {
            super("No handler registered for " + command.getClass().getName());
        }
    }
}
