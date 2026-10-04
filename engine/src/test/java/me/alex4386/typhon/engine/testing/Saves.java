package me.alex4386.typhon.engine.testing;

import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;

/** Test helper: complete in-memory save of an engine. */
public final class Saves {
    private Saves() {}

    public static InMemorySaveStore save(Engine engine) {
        InMemorySaveStore store = new InMemorySaveStore();
        engine.save(store);
        return store;
    }
}
