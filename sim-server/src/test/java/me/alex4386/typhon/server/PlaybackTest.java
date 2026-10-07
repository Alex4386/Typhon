package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import me.alex4386.typhon.engine.dike.DikeEvents.DikeStarted;
import me.alex4386.typhon.engine.magma.MagmaEvents.Cause;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionEnded;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.sim.EngineRunner;
import org.junit.jupiter.api.Test;

/** The playback policy: slow down when an eruption starts, restore the speed when it ends. */
class PlaybackTest {
    /** A runner that is never started: the policy only switches its mode and speed. */
    static EngineRunner runner(EngineRunner.Mode mode, double speed) {
        return new EngineRunner(Engine.builder(1).build(), EngineRunner.Options.defaults().withMode(mode).withSpeed(speed), t -> {});
    }

    static EngineFrame frame(double time, EngineEvent... events) {
        return new EngineFrame(0, Math.round(time * 1e6), List.of(events));
    }

    static EruptionStarted start(double t, String v) {
        return new EruptionStarted(t, v, 15, Cause.AUTOMATIC);
    }

    static EruptionEnded end(double t, String v) {
        return new EruptionEnded(t, v, 1e6, 600, Cause.AUTOMATIC);
    }

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void anEruptionSlowsPlaybackDownAndItsEndRestoresTheSpeed() {
        Playback p = new Playback();
        EngineRunner r = runner(EngineRunner.Mode.REALTIME, 86_400);
        p.observe(frame(10), r);
        assertEquals(86_400, r.speed());
        p.observe(frame(20, start(20, "a")), r);
        assertEquals(EngineRunner.Mode.REALTIME, r.mode());
        assertEquals(20, r.speed(), "eruption speed");
        assertEquals("eruption", p.slowedBy());
        assertEquals(86_400, p.json().get("resumeSpeed").getAsDouble());
        p.observe(frame(500), r);
        assertEquals(20, r.speed(), "still erupting");
        p.observe(frame(900, end(900, "a")), r);
        assertEquals(86_400, r.speed(), "previous speed back");
        assertFalse(p.slowed());
    }

    @Test
    void maxComesBackAsMaxAndTwoVolcanoesKeepItSlowUntilBothEnd() {
        Playback p = new Playback();
        EngineRunner r = runner(EngineRunner.Mode.UNBOUNDED, 3600);
        p.observe(frame(1, start(1, "a")), r);
        assertEquals(EngineRunner.Mode.REALTIME, r.mode());
        p.observe(frame(2, start(2, "b")), r);
        p.observe(frame(3, end(3, "a")), r);
        assertTrue(p.slowed(), "b still erupts");
        p.observe(frame(4, end(4, "b")), r);
        assertEquals(EngineRunner.Mode.UNBOUNDED, r.mode());
    }

    @Test
    void slowerPlaybackIsLeftAloneAndAPausedRunnerStaysPaused() {
        Playback p = new Playback();
        EngineRunner slow = runner(EngineRunner.Mode.REALTIME, 5);
        p.observe(frame(1, start(1, "a")), slow);
        assertEquals(5, slow.speed());
        assertFalse(p.slowed());

        Playback q = new Playback();
        EngineRunner paused = runner(EngineRunner.Mode.PAUSED, 3600);
        q.observe(frame(1, start(1, "a")), paused);
        assertEquals(EngineRunner.Mode.PAUSED, paused.mode());
    }

    @Test
    void choosingASpeedWhileSlowedKeepsIt() {
        Playback p = new Playback();
        EngineRunner r = runner(EngineRunner.Mode.REALTIME, 1e6);
        p.observe(frame(1, start(1, "a")), r);
        r.realtime(60);
        p.userChanged();
        p.observe(frame(2, end(2, "a")), r);
        assertEquals(60, r.speed(), "the user's choice stands");
    }

    @Test
    void policyIsAppliedAndEventsSlowDownForTheirHold() {
        Playback p = new Playback();
        EngineRunner r = runner(EngineRunner.Mode.REALTIME, 1e6);
        p.apply(json("{\"slowOnEruption\":false,\"eruptionSpeed\":600,\"slowOnEvents\":[\"dike\"],\"eventHoldSeconds\":100}"), r);
        p.observe(frame(1, start(1, "a")), r);
        assertEquals(1e6, r.speed(), "eruptions no longer slow down");
        p.observe(frame(5, new DikeStarted(5, "a", 1, new BlockPos(0, 0, 0), 10)), r);
        assertEquals(600, r.speed());
        assertEquals("dike", p.slowedBy());
        p.observe(frame(50), r);
        assertEquals(600, r.speed(), "held");
        p.observe(frame(200), r);
        assertEquals(1e6, r.speed(), "hold over");
        assertThrows(IllegalArgumentException.class, () -> p.apply(json("{\"slowOnEvents\":[\"meteor\"]}"), r));
    }

    @Test
    void turningThePolicyOffEndsItsSlowDown() {
        Playback p = new Playback();
        EngineRunner r = runner(EngineRunner.Mode.REALTIME, 1e6);
        p.observe(frame(1, start(1, "a")), r);
        p.apply(json("{\"slowOnEruption\":false}"), r);
        assertEquals(1e6, r.speed());
        assertFalse(p.slowed());
    }
}
