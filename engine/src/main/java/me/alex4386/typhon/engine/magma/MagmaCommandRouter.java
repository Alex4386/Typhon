package me.alex4386.typhon.engine.magma;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.magma.MagmaCommands.InjectRecharge;
import me.alex4386.typhon.engine.magma.MagmaCommands.MagmaCommand;
import me.alex4386.typhon.engine.magma.MagmaCommands.SetSupplyRate;
import me.alex4386.typhon.engine.magma.MagmaCommands.StartEruption;
import me.alex4386.typhon.engine.magma.MagmaCommands.StopEruption;

/**
 * Routes {@link MagmaCommand}s to the chamber with the matching volcano id.
 *
 * <p>{@link CommandBus} allows a single handler per command type, but an engine may host several
 * chambers. The first chamber registered on a bus installs one router; later chambers join it.
 */
final class MagmaCommandRouter {
    private static final Map<CommandBus, MagmaCommandRouter> ROUTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<String, MagmaChamber> chambers = new HashMap<>();

    static void join(CommandBus bus, MagmaChamber chamber) {
        MagmaCommandRouter router = ROUTERS.computeIfAbsent(bus, MagmaCommandRouter::install);
        String id = chamber.config().volcanoId();
        if (router.chambers.putIfAbsent(id, chamber) != null) {
            throw new IllegalArgumentException("Duplicate magma chamber for volcano " + id);
        }
    }

    private static MagmaCommandRouter install(CommandBus bus) {
        MagmaCommandRouter router = new MagmaCommandRouter();
        bus.register(SetSupplyRate.class, router::route);
        bus.register(InjectRecharge.class, router::route);
        bus.register(StartEruption.class, router::route);
        bus.register(StopEruption.class, router::route);
        return router;
    }

    private void route(MagmaCommand command) {
        MagmaChamber chamber = chambers.get(command.volcanoId());
        if (chamber == null) {
            throw new IllegalArgumentException("No magma chamber for volcano " + command.volcanoId());
        }
        chamber.handle(command);
    }
}
