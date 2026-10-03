package me.alex4386.typhon.engine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandBusTest {
    record Targeted(String target, int value) implements EngineCommand {}

    @Test
    void dispatchesToEveryHandlerInRegistrationOrder() {
        CommandBus bus = new CommandBus();
        List<String> seen = new ArrayList<>();
        for (String id : List.of("a", "b")) {
            bus.register(Targeted.class, c -> {
                if (c.target().equals(id)) seen.add(id + "=" + c.value());
            });
        }
        bus.register(Targeted.class, c -> seen.add("all=" + c.value()));

        bus.dispatch(new Targeted("b", 1));
        bus.dispatch(new Targeted("a", 2));

        assertEquals(List.of("b=1", "all=1", "a=2", "all=2"), seen);
    }

    @Test
    void unhandledTypeFails() {
        assertThrows(CommandBus.UnhandledCommandException.class, () -> new CommandBus().dispatch(new Targeted("x", 0)));
    }
}
