package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.alert.AlertLevel;
import me.alex4386.typhon.engine.lava.LavaEvents.LavaFlowFront;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import org.junit.jupiter.api.Test;

/**
 * The base step is a numerical resolution, not physics: halving or doubling it must leave the
 * physical outcome of the same scenario essentially unchanged.
 */
class BaseStepInvarianceTest {
    record Outcome(double erupted, double emitted, double lava, double frontM, AlertLevel level, int quakes) {}

    static Outcome run(long baseStepMicros, double seconds) {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder("test", List.of(VolcanoSystemTest.CRATER), terrain, lava)
                .chamber(VolcanoSystemTest.basalt())
                .scaling(VolcanoScaling.DEFAULT)
                .dikesEnabled(false)
                .build();
        Engine.Builder builder = Engine.builder(7).baseStepMicros(baseStepMicros).add(terrain);
        volcano.addTo(builder).add(lava);
        Engine engine = builder.build();
        engine.submit(VolcanoSystemTest.cone());

        double front = 0;
        int quakes = 0;
        for (EngineFrame frame : engine.runFor(seconds)) {
            for (EngineEvent e : frame.events()) {
                if (e instanceof LavaFlowFront f) front = f.lengthM();
                if (e instanceof me.alex4386.typhon.engine.seismic.SeismicEvent) quakes++;
            }
        }
        return new Outcome(volcano.chamber().eruptedVolume(), lava.emittedVolume(),
                lava.totalLavaVolume() + lava.solidifiedVolume(), front, volcano.alert().level(), quakes);
    }

    @Test
    void outcomeDoesNotDependOnTheBaseStep() {
        Outcome fine = run(50_000, 900); // the chamber fails after ≈ 11 min
        Outcome coarse = run(100_000, 900);

        // The chamber, seismicity and alert subsystems step every 0.5–1 s at either resolution.
        assertEquals(fine.erupted(), coarse.erupted(), 1e-9 * Math.max(1, fine.erupted()));
        assertEquals(fine.level(), coarse.level());
        assertEquals(AlertLevel.ERUPTING, fine.level());
        assertEquals(fine.quakes(), coarse.quakes());

        // Lava steps every base step: same volume fed in, comparable flow.
        assertTrue(fine.emitted() > 0);
        assertEquals(fine.emitted(), coarse.emitted(), 0.02 * fine.emitted());
        assertEquals(fine.lava(), coarse.lava(), 0.02 * fine.lava());
        assertTrue(fine.frontM() > 0 && coarse.frontM() > 0);
        assertEquals(fine.frontM(), coarse.frontM(), 0.35 * fine.frontM(),
                "front " + fine.frontM() + " m vs " + coarse.frontM() + " m");
    }

    @Test
    void fixedBaseStepIsDeterministic() {
        assertEquals(run(100_000, 60), run(100_000, 60));
    }
}
