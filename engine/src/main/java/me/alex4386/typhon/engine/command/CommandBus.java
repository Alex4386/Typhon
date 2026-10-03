package me.alex4386.typhon.engine.command;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Dispatches commands to the handler registered for their exact type. */
public final class CommandBus {
    private final Map<Class<?>, Consumer<? super EngineCommand>> handlers = new HashMap<>();

    public <T extends EngineCommand> void register(Class<T> type, Consumer<? super T> handler) {
        if (handlers.containsKey(type)) {
            throw new IllegalStateException("Handler already registered for " + type.getName());
        }
        handlers.put(type, command -> handler.accept(type.cast(command)));
    }

    public void dispatch(EngineCommand command) {
        Consumer<? super EngineCommand> handler = handlers.get(command.getClass());
        if (handler == null) {
            throw new UnhandledCommandException(command);
        }
        handler.accept(command);
    }

    public static final class UnhandledCommandException extends RuntimeException {
        public UnhandledCommandException(EngineCommand command) {
            super("No handler registered for " + command.getClass().getName());
        }
    }
}
