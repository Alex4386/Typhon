package me.alex4386.typhon.engine.worlds;

import java.util.List;

/**
 * The one place that decides what a change to a world or volcano definition needs. Everything else
 * derives from it: {@link ConfigChanges} (state compatibility on reopen), live retuning
 * ({@link World#reconfigureLive}), and hosts' plans, schemas, responses and audits.
 *
 * <ul>
 *   <li>{@link Kind#LIVE}: taken in place at a step boundary; nothing restarts. The default: every
 *       physical parameter acts from now on.
 *   <li>{@link Kind#RELOAD}: the affected part is rebuilt from the new definition with its state kept
 *       (inputs read only when ground is generated or the world is assembled, e.g. geology, edifice).
 *   <li>{@link Kind#REINIT}: the target ({@link Target}) starts over from the new definition, because the
 *       change is an initial condition, a position or a size the saved state is laid out on, or adds or
 *       removes a part. A world-level reinit cannot be applied to a running world at all.
 * </ul>
 */
public final class ConfigImpact {
    private ConfigImpact() {}

    public enum Kind { LIVE, RELOAD, REINIT }

    /** What a reload or reinit rebuilds or resets. */
    public enum Target {
        /** Nothing (live). */
        NONE,
        /** The whole world (refused for a running world). */
        WORLD,
        /** World inputs read at assembly and generation (geology, terrain, geotherm, aquifer). */
        WORLD_INPUTS,
        /** Every subsystem of the volcano. */
        VOLCANO,
        /** The volcano's edifice description (used for ground generated from now on). */
        EDIFICE,
        /** The volcano's airborne ash and ash grid. */
        TEPHRA,
        /** The volcano's hot springs, fumaroles and their grid. */
        GEOTHERMAL,
        /** The volcano's fine crater surface. */
        DETAIL,
        /** One further chamber of the volcano's plumbing (the impact's {@code subject}). */
        CHAMBER,
        /** The pathways between the volcano's chambers. */
        PLUMBING;

        /**
         * Subsystem ids of {@code volcanoId} a reset of this target clears (empty for whole volcanoes and
         * non-resets); {@code subject} names the chamber of a {@link #CHAMBER} target.
         */
        public List<String> subsystemIds(String volcanoId, String subject) {
            return switch (this) {
                case TEPHRA -> List.of("tephra:" + volcanoId);
                case GEOTHERMAL -> List.of("geothermal:" + volcanoId);
                case DETAIL -> List.of("detail:" + volcanoId);
                case CHAMBER -> List.of(me.alex4386.typhon.engine.magma.MagmaChamber.id(volcanoId, subject));
                case PLUMBING -> List.of(me.alex4386.typhon.engine.magma.plumbing.MagmaTransfer.defaultId(volcanoId));
                default -> List.of();
            };
        }
    }

    /**
     * What a change needs.
     *
     * @param reason why it is not live (shown to users), {@code null} for live changes
     * @param subject the part it concerns where the target needs one (a chamber id), else {@code null}
     */
    public record Impact(Kind kind, Target target, String reason, String subject) {
        public static final Impact LIVE = new Impact(Kind.LIVE, Target.NONE, null, null);

        public Impact(Kind kind, Target target, String reason) {
            this(kind, target, reason, null);
        }

        /** Whether this state survives the change (live or reload). */
        public boolean keepsState() {
            return kind != Kind.REINIT;
        }

        /** A sentence for users about the consequence, for a volcano called {@code name} (or the world). */
        public String message(String name) {
            String who = name == null ? "the world" : name;
            String chamber = subject == null ? "a chamber" : "chamber " + subject;
            String whoseChamber = subject == null ? "one of " + who + "'s chambers" : who + "'s chamber " + subject;
            return switch (kind) {
                case LIVE -> "Applies at once; the simulation carries on.";
                case RELOAD -> switch (target) {
                    case CHAMBER -> "Adds " + chamber + " to " + who + " after a short pause; everything already simulated carries on.";
                    case PLUMBING -> "Rebuilds " + who + "'s magma pathways" + (reason == null ? "" : " (" + reason + ")")
                            + " after a short pause; every chamber keeps its magma.";
                    case VOLCANO -> ADDED.equals(reason)
                            ? "Adds " + who + " after a short pause; everything already simulated carries on."
                            : "Rebuilds " + who + " (" + reason + ") after a short pause; everything simulated is kept.";
                    case EDIFICE -> "Rebuilds " + who + "'s edifice description after a short pause; everything simulated is kept"
                            + " (only ground generated from now on uses it).";
                    default -> "Rebuilds the world's inputs" + (reason == null ? "" : " (" + reason + ")") + " after a short pause;"
                            + " everything simulated is kept (only ground generated from now on uses it).";
                };
                case REINIT -> switch (target) {
                    case CHAMBER -> REMOVED.equals(reason)
                            ? "Removes " + whoseChamber + ": its magma is gone; the other chambers carry on."
                            : "Restarts " + whoseChamber + " from its new settings (" + reason + "); the other chambers carry on.";
                    case PLUMBING -> "Restarts " + who + "'s magma pathways (" + reason + "); every chamber keeps its magma.";
                    case WORLD -> "Cannot change in a running world (" + reason + "); create a new world with it.";
                    case TEPHRA -> "Clears " + who + "'s airborne ash and restarts its ash grid at the new size (" + reason
                            + "); ash already on the ground stays.";
                    case GEOTHERMAL -> "Restarts " + who + "'s hot springs and fumaroles on the new grid (" + reason
                            + "); they form again over time.";
                    case DETAIL -> "Rebuilds " + who + "'s fine crater surface (" + reason + ") from the terrain.";
                    case VOLCANO -> REMOVED.equals(reason)
                            ? "Removes " + who + ": its magma system, dikes, vents and eruption history are deleted; the landscape and"
                                    + " everything it erupted stay."
                            : restarts(who, reason);
                    default -> restarts(who, reason);
                };
            };
        }
    }

    private static String restarts(String who, String reason) {
        return "Restarts " + who + " from its new settings (" + reason + "): magma chamber, dikes, ash and eruption history start"
                + " over; the landscape and lava already erupted are kept.";
    }

    private record Rule(String pattern, Impact impact) {}

    private static Impact reinit(Target target, String reason) {
        return new Impact(Kind.REINIT, target, reason);
    }

    private static Impact reload(Target target, String reason) {
        return new Impact(Kind.RELOAD, target, reason);
    }

    /** Volcano-definition rules, first match wins; anything else is live. */
    private static final List<Rule> VOLCANO = List.of(
            new Rule("id", reinit(Target.VOLCANO, "its identity")),
            new Rule("vents*", reinit(Target.VOLCANO, "vents are its geometry")),
            new Rule("magma.chamber.center*", reinit(Target.VOLCANO, "the chamber's position")),
            new Rule("magma.chamber.initial*", reinit(Target.VOLCANO, "an initial condition of the chamber")),
            new Rule("magma.chamber.volume", reinit(Target.VOLCANO, "the chamber's starting size; it then grows and shrinks as state")),
            new Rule("magma.conduit.initialOpenness", reinit(Target.VOLCANO, "an initial condition of the conduit")),
            new Rule("dikes", reinit(Target.VOLCANO, "adds or removes dikes")),
            new Rule("dikes.enabled", reinit(Target.VOLCANO, "adds or removes dikes")),
            new Rule("geothermal", reinit(Target.VOLCANO, "adds or removes hot springs")),
            new Rule("geothermal.enabled", reinit(Target.VOLCANO, "adds or removes hot springs")),
            new Rule("massFlows", reinit(Target.VOLCANO, "adds or removes pyroclastic flows and lahars")),
            new Rule("massFlows.*enabled", reinit(Target.VOLCANO, "adds or removes pyroclastic flows and lahars")),
            new Rule("deformation", reinit(Target.VOLCANO, "adds or removes ground deformation")),
            new Rule("deformation.enabled", reinit(Target.VOLCANO, "adds or removes ground deformation")),
            new Rule("tephra.cellSizeM", reinit(Target.TEPHRA, "the ash grid's cell size")),
            new Rule("tephra.gridCells", reinit(Target.TEPHRA, "the ash grid's size")),
            new Rule("geothermal.center*", reinit(Target.GEOTHERMAL, "the hot-spring field's position")),
            new Rule("geothermal.radiusM", reinit(Target.GEOTHERMAL, "the hot-spring grid's size")),
            new Rule("geothermal.cellSizeM", reinit(Target.GEOTHERMAL, "the hot-spring grid's cell size")),
            new Rule("detail*", reinit(Target.DETAIL, "the crater surface's resolution")),
            new Rule("edifice*", reload(Target.EDIFICE, "edifice")));

    /** World-definition rules, first match wins; anything else is live. */
    private static final List<Rule> WORLD = List.of(
            new Rule("seed", reinit(Target.WORLD, "the random seed")),
            new Rule("baseStepMs", reinit(Target.WORLD, "the base time step")),
            new Rule("grid*", reinit(Target.WORLD, "the grid the world is laid out on")),
            new Rule("seaLevel", reinit(Target.WORLD, "the sea level of the generated ground")),
            new Rule("subsurface.levels", reinit(Target.WORLD, "the underground solver's levels")),
            new Rule("subsurface.firstLevelM", reinit(Target.WORLD, "the underground solver's levels")),
            new Rule("subsurface.levelGrowth", reinit(Target.WORLD, "the underground solver's levels")),
            new Rule("expansion.tileColumns", reinit(Target.WORLD, "the expansion tile size")),
            new Rule("geology*", reload(Target.WORLD_INPUTS, "geology")),
            new Rule("geotherm*", reload(Target.WORLD_INPUTS, "geotherm")),
            new Rule("aquifer*", reload(Target.WORLD_INPUTS, "aquifer")),
            new Rule("terrain*", reload(Target.WORLD_INPUTS, "terrain source")));

    /** What a change to a volcano definition at dotted {@code path} needs. */
    public static Impact volcano(String path) {
        if (path.equals("*")) return volcanoRemoved(); // the whole volcano (an added one is diffed as such)
        if (path.startsWith(CHAMBERS + "[")) return chamber(subjectOf(path), afterSubject(path));
        if (path.startsWith(CONNECTIONS + "[")) return connection(afterSubject(path));
        return classify(path, VOLCANO);
    }

    /** List paths keyed by element id (diffed element by element: {@code magma.chambers[deep].volume}). */
    public static final String CHAMBERS = "magma.chambers";
    public static final String CONNECTIONS = "magma.connections";

    /** Whether the list at {@code path} is diffed by its elements' {@code id}. */
    public static boolean keyedList(String path) {
        return path.equals(CHAMBERS) || path.equals(CONNECTIONS);
    }

    /** An element of a keyed list added ({@code before} absent) or removed. */
    public static Impact listElement(String listPath, String id, boolean added) {
        if (listPath.equals(CHAMBERS)) return added ? new Impact(Kind.RELOAD, Target.CHAMBER, ADDED, id) : new Impact(Kind.REINIT, Target.CHAMBER, REMOVED, id);
        return new Impact(Kind.RELOAD, Target.PLUMBING, added ? "a pathway added" : "a pathway removed", null);
    }

    /** A further chamber: its position, starting size and initial magma reset it alone; the rest is live. */
    private static Impact chamber(String id, String field) {
        if (field.startsWith("center") || field.equals("lithostaticDepth")) return new Impact(Kind.REINIT, Target.CHAMBER, "its position", id);
        if (field.equals("volume")) return new Impact(Kind.REINIT, Target.CHAMBER, "its starting size", id);
        if (field.startsWith("initial")) return new Impact(Kind.REINIT, Target.CHAMBER, "an initial condition", id);
        if (field.isEmpty()) return new Impact(Kind.REINIT, Target.CHAMBER, "replaced", id);
        return Impact.LIVE;
    }

    /** A pathway: re-wiring its ends rebuilds the pathways (chambers keep their magma); its size and state are live. */
    private static Impact connection(String field) {
        if (field.equals("from") || field.equals("to") || field.isEmpty()) return new Impact(Kind.RELOAD, Target.PLUMBING, "re-wired", null);
        return Impact.LIVE;
    }

    private static String subjectOf(String path) {
        return path.substring(path.indexOf('[') + 1, path.indexOf(']'));
    }

    private static String afterSubject(String path) {
        int close = path.indexOf(']');
        return close + 2 <= path.length() ? path.substring(Math.min(path.length(), close + 2)) : "";
    }

    /** Reason of {@link #volcanoAdded()}. */
    public static final String ADDED = "added";
    /** Reason of {@link #volcanoRemoved()}. */
    public static final String REMOVED = "removed";

    /** A volcano added to a running world: built after a short pause, nothing else changes. */
    public static Impact volcanoAdded() {
        return reload(Target.VOLCANO, ADDED);
    }

    /** A volcano removed from a running world. */
    public static Impact volcanoRemoved() {
        return reinit(Target.VOLCANO, REMOVED);
    }

    /** What a change to the world definition at dotted {@code path} needs. */
    public static Impact world(String path) {
        return classify(path, WORLD);
    }

    private static Impact classify(String path, List<Rule> rules) {
        for (Rule r : rules) {
            if (ConfigChanges.matches(r.pattern, path)) return r.impact;
        }
        return Impact.LIVE;
    }
}
