package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import me.alex4386.typhon.engine.alert.AlertEvents.AlertLevelChanged;
import me.alex4386.typhon.engine.dike.DikeEvents.DikeStarted;
import me.alex4386.typhon.engine.dike.DikeEvents.FissureOpened;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionEnded;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.AvalancheStarted;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.LaharStarted;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.PdcStarted;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.EngineRunner;

/**
 * Playback of one session: how fast time passes on screen, and the policy that slows it down while
 * something happens. Speed is seconds of time per wall-clock second; it never changes the physics
 * (the engine picks its own step lengths), only when steps run.
 *
 * <p>With {@link #slowOnEruption} on, the start of an eruption switches to {@link #eruptionSpeed}
 * (if playback is faster) and the previous speed comes back once no volcano erupts any more. Events
 * chosen in {@link #slowOnEvents} slow down the same way and hold for {@link #eventHoldSeconds} of
 * time after the latest one. Changing the speed by hand while slowed keeps the new speed (the
 * slow-down is over).
 *
 * <p>{@link #observe} runs on the engine thread after every step, so a slow-down takes effect at the
 * frame where the eruption starts whatever the speed.
 */
final class Playback {
    static final double MIN_SPEED = 1;
    static final double MAX_SPEED = 1e7;
    /** Event kinds a session can slow down on (besides eruptions). */
    static final List<String> EVENT_KINDS = List.of("unrest", "dike", "fissure", "pyroclasticFlow", "lahar", "avalanche");

    private boolean slowOnEruption = true;
    private double eruptionSpeed = 20;
    private final Set<String> slowOnEvents = new TreeSet<>();
    private double eventHoldSeconds = 3600;

    /** What the current slow-down is for ({@code eruption} or an event kind), or null. */
    private String slowedBy;
    private EngineRunner.Mode resumeMode;
    private double resumeSpeed;
    private final Set<String> erupting = new TreeSet<>();
    private double eventUntil = Double.NEGATIVE_INFINITY;

    static double clampSpeed(double speed) {
        if (!(speed > 0)) throw new IllegalArgumentException("speed must be positive");
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    /** Volcanoes erupting when the runner starts (a restored or reopened session). */
    synchronized void reset(Collection<String> eruptingNow) {
        erupting.clear();
        erupting.addAll(eruptingNow);
        eventUntil = Double.NEGATIVE_INFINITY;
        slowedBy = null;
    }

    /** The user changed the speed or mode: their choice stands, any slow-down is over. */
    synchronized void userChanged() {
        slowedBy = null;
    }

    /** Engine thread: tracks eruptions and triggering events; slows down or restores the runner. */
    synchronized void observe(EngineFrame frame, EngineRunner runner) {
        String trigger = null;
        for (EngineEvent e : frame.events()) {
            if (e instanceof EruptionStarted s) {
                erupting.add(s.volcanoId());
                if (slowOnEruption) trigger = "eruption";
            } else if (e instanceof EruptionEnded end) {
                erupting.remove(end.volcanoId());
            } else {
                String kind = kind(e);
                if (kind != null && slowOnEvents.contains(kind)) {
                    eventUntil = Math.max(eventUntil, e.time() + eventHoldSeconds);
                    if (trigger == null) trigger = kind;
                }
            }
        }
        double end = frame.timeMicros() / 1e6;
        if (trigger != null && slowedBy == null) {
            EngineRunner.Mode mode = runner.mode();
            boolean faster = mode == EngineRunner.Mode.UNBOUNDED
                    || (mode == EngineRunner.Mode.REALTIME && runner.speed() > eruptionSpeed);
            if (faster) {
                resumeMode = mode;
                resumeSpeed = runner.speed();
                slowedBy = trigger;
                runner.realtime(eruptionSpeed);
            }
        } else if (slowedBy != null && "eruption".equals(trigger)) {
            slowedBy = trigger; // an eruption during an event slow-down: it now lasts as long as the eruption
        }
        boolean active = (slowOnEruption && !erupting.isEmpty()) || end < eventUntil;
        if (slowedBy != null && !active) {
            slowedBy = null;
            if (runner.mode() != EngineRunner.Mode.PAUSED) {
                runner.realtime(resumeSpeed);
                if (resumeMode == EngineRunner.Mode.UNBOUNDED) runner.unbounded();
            }
        }
    }

    /** The event kind {@code e} counts as, or null. */
    static String kind(EngineEvent e) {
        if (e instanceof AlertLevelChanged a) return a.previous() == null || a.current().ordinal() > a.previous().ordinal() ? "unrest" : null;
        if (e instanceof DikeStarted) return "dike";
        if (e instanceof FissureOpened) return "fissure";
        if (e instanceof PdcStarted) return "pyroclasticFlow";
        if (e instanceof LaharStarted) return "lahar";
        if (e instanceof AvalancheStarted) return "avalanche";
        return null;
    }

    /**
     * Applies a {@code setPlaybackPolicy} message (fields optional): {@code slowOnEruption},
     * {@code eruptionSpeed}, {@code slowOnEvents} (array of {@link #EVENT_KINDS}),
     * {@code eventHoldSeconds}.
     */
    synchronized void apply(JsonObject msg, EngineRunner runner) {
        Boolean slow = msg.has("slowOnEruption") && !msg.get("slowOnEruption").isJsonNull()
                ? msg.get("slowOnEruption").getAsBoolean() : null;
        Double speed = Json.dbl(msg, "eruptionSpeed");
        Double hold = Json.dbl(msg, "eventHoldSeconds");
        Set<String> events = null;
        if (msg.has("slowOnEvents") && msg.get("slowOnEvents").isJsonArray()) {
            events = new TreeSet<>();
            for (JsonElement k : msg.getAsJsonArray("slowOnEvents")) {
                String kind = k.getAsString();
                if (!EVENT_KINDS.contains(kind)) {
                    throw new IllegalArgumentException("Unknown event kind '" + kind + "'; known: " + EVENT_KINDS);
                }
                events.add(kind);
            }
        }
        if (hold != null && !(hold >= 0)) throw new IllegalArgumentException("eventHoldSeconds must be >= 0");
        if (speed != null) eruptionSpeed = clampSpeed(speed);
        if (slow != null) slowOnEruption = slow;
        if (events != null) {
            slowOnEvents.clear();
            slowOnEvents.addAll(events);
        }
        if (hold != null) eventHoldSeconds = hold;
        // Switching the policy off ends a slow-down it caused; a new eruption speed applies at once.
        if (slowedBy != null && runner != null) {
            boolean stillWanted = slowedBy.equals("eruption") ? slowOnEruption : slowOnEvents.contains(slowedBy);
            if (!stillWanted) {
                eventUntil = Double.NEGATIVE_INFINITY;
                slowedBy = null;
                runner.realtime(resumeSpeed);
                if (resumeMode == EngineRunner.Mode.UNBOUNDED) runner.unbounded();
            } else if (speed != null && runner.mode() == EngineRunner.Mode.REALTIME) {
                runner.realtime(eruptionSpeed);
            }
        }
    }

    /** The policy and the slow-down state, for the {@code clock} message. */
    synchronized JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("slowOnEruption", slowOnEruption);
        o.add("eruptionSpeed", Json.num(eruptionSpeed));
        JsonArray events = new JsonArray();
        for (String k : slowOnEvents) events.add(k);
        o.add("slowOnEvents", events);
        o.add("eventHoldSeconds", Json.num(eventHoldSeconds));
        o.addProperty("slowed", slowedBy != null);
        if (slowedBy != null) {
            o.addProperty("slowedBy", slowedBy);
            o.add("resumeSpeed", Json.num(resumeSpeed));
            o.addProperty("resumeMode", resumeMode.name());
        }
        return o;
    }

    synchronized boolean slowed() {
        return slowedBy != null;
    }

    synchronized String slowedBy() {
        return slowedBy;
    }

    synchronized double eruptionSpeed() {
        return eruptionSpeed;
    }

    synchronized boolean slowOnEruption() {
        return slowOnEruption;
    }
}
