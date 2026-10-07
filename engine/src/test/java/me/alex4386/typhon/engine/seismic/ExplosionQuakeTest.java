package me.alex4386.typhon.engine.seismic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.testing.StubMagmaState;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class ExplosionQuakeTest {
    static SeismicityModel model() {
        return new SeismicityModel(SeismicConfig.builder("v", new BlockPos(0, 100, 0)).build(), StubMagmaState.basalt(), 1);
    }

    static List<SeismicEvent> explosions(Engine engine, int ticks) {
        List<SeismicEvent> out = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            for (EngineEvent e : engine.step().events()) {
                if (e instanceof SeismicEvent s && s.type() == SeismicEventType.EXPLOSION) out.add(s);
            }
        }
        return out;
    }

    @Test
    void biggerExplosionsMakeBiggerQuakes() {
        SeismicityModel model = model();
        Engine engine = Engine.builder(0).add(model).build();
        model.queueExplosion(new Point3(0.5, 98.5, 0.5), 1e10);
        model.queueExplosion(new Point3(0.5, 98.5, 0.5), 1e13);
        List<SeismicEvent> quakes = explosions(engine, 40);
        assertEquals(2, quakes.size());
        assertTrue(quakes.get(1).magnitude() > quakes.get(0).magnitude());
        // 1e13 J × 1e-4 → M = (9 − 4.8) / 1.5 = 2.8
        assertEquals(2.8, quakes.get(1).magnitude(), 1e-9);
    }

    @Test
    void queuedExplosionsSurviveSaveAndRestore() {
        SeismicityModel model = model();
        Engine engine = Engine.builder(0).add(model).build();
        engine.step(); // step at tick 0 consumes nothing; the next step is later
        model.queueExplosion(new Point3(0.5, 98.5, 0.5), 1e11);
        InMemorySaveStore saved = Saves.save(engine);

        SeismicityModel restored = model();
        Engine resumed = Engine.builder(0).add(restored).restore(saved).build();
        assertEquals(explosions(engine, 60), explosions(resumed, 60));
    }
}
