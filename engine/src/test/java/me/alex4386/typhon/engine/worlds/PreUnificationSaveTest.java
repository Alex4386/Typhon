package me.alex4386.typhon.engine.worlds;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveFormat;
import org.junit.jupiter.api.Test;

/**
 * Worlds saved before the one-clock engine (no per-subsystem schedule, no stride in {@code meta.json},
 * time-compression keys in the definition) open and keep running.
 */
class PreUnificationSaveTest {
    /** Strips what saves gained with adaptive steps, leaving the meta.json an older engine wrote. */
    static void makePreUnification(InMemorySaveStore state) {
        JsonObject meta = JsonParser.parseString(new String(state.read(SaveFormat.META), StandardCharsets.UTF_8))
                .getAsJsonObject();
        meta.remove("lastStrideQuanta");
        for (JsonElement e : meta.getAsJsonArray("subsystems")) e.getAsJsonObject().remove("nextQuanta");
        state.write(SaveFormat.META, SaveFormat.jsonBytes(meta));
    }

    @Test
    void aWorldSavedBeforeTheOneClockOpensAndRuns() {
        InMemorySaveStore state = new InMemorySaveStore();
        InMemorySaveStore history = new InMemorySaveStore();
        World original = World.create(WorldTest.world(), WorldTest.twins(), WorldTest.terrain(WorldTest.world(), WorldTest.twins()),
                state, history);
        original.engine().runFor(30);
        original.save();
        makePreUnification(state);

        // its definition still carries the retired compression keys (ignored with a warning)
        WorldDefinition old = WorldDefinition.parse(Yaml.parse("world.yaml", """
                name: twin
                seed: 1
                grid: {metersPerColumn: 4}
                scaling: {plumeMetersPerBlock: 100, dormantTimeCompression: 5000, eruptiveTimeCompression: 20}
                terrain: {source: test}
                """));
        assertTrue(Double.isFinite(old.scaling().plumeMetersPerBlock()));

        World reopened = World.reopen(WorldTest.world(), WorldTest.twins(), state, history, World.ChangePolicy.REJECT);
        double t = reopened.engine().time();
        reopened.engine().runFor(600);
        assertTrue(reopened.engine().time() >= t + 600, "the reopened world keeps running");
    }
}
