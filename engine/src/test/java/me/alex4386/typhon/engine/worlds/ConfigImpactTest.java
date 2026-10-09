package me.alex4386.typhon.engine.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.worlds.ConfigImpact.Kind;
import me.alex4386.typhon.engine.worlds.ConfigImpact.Target;
import org.junit.jupiter.api.Test;

/** The classification table: physics is live, initial conditions/layout/presence reset, generation inputs reload. */
class ConfigImpactTest {
    private static void volcano(String path, Kind kind, Target target) {
        ConfigImpact.Impact i = ConfigImpact.volcano(path);
        assertEquals(kind, i.kind(), path);
        assertEquals(target, i.target(), path);
    }

    private static void world(String path, Kind kind, Target target) {
        ConfigImpact.Impact i = ConfigImpact.world(path);
        assertEquals(kind, i.kind(), path);
        assertEquals(target, i.target(), path);
    }

    @Test
    void volcanoTable() {
        for (String live : new String[] {"name", "active", "magma.chamber.supplyRate", "magma.chamber.rechargeCo2Wt", "magma.chamber.tensileStrengthMPa",
                "magma.chamber.lithostaticDepth", "magma.chamber.wallRuptureRatio", "magma.chamber.coolingTimescale",
                "magma.conduit.fragmentationPorosity", "magma.conduit.plugStrengthMPa", "dikes.shearModulusPa",
                "massFlows.pdc.frictionCoefficient", "massFlows.lahar.erosionCoefficient", "tephra.diffusivity",
                "tephra.initialWindSpeed", "geothermal.hotSpringMinC", "deformation.stations"}) {
            volcano(live, Kind.LIVE, Target.NONE);
        }
        volcano("magma.chamber.initialTemperatureC", Kind.REINIT, Target.VOLCANO);
        volcano("magma.chamber.center.x", Kind.REINIT, Target.VOLCANO);
        volcano("magma.chamber.volume", Kind.REINIT, Target.VOLCANO);
        volcano("magma.conduit.initialOpenness", Kind.REINIT, Target.VOLCANO);
        volcano("vents", Kind.REINIT, Target.VOLCANO);
        volcano("dikes.enabled", Kind.REINIT, Target.VOLCANO);
        volcano("massFlows.pdc.enabled", Kind.REINIT, Target.VOLCANO);
        volcano("*", Kind.REINIT, Target.VOLCANO);
        volcano("tephra.gridCells", Kind.REINIT, Target.TEPHRA);
        volcano("geothermal.radiusM", Kind.REINIT, Target.GEOTHERMAL);
        volcano("geothermal.center.y", Kind.REINIT, Target.GEOTHERMAL);
        volcano("detail.metersPerCell", Kind.REINIT, Target.DETAIL);
        volcano("edifice.material", Kind.RELOAD, Target.EDIFICE);
    }

    @Test
    void worldTable() {
        for (String live : new String[] {"name", "climate.rainfallMmPerHour",
                "lava.emissivity", "subsurface.macroStepSeconds", "subsurface.specificYield",
                "expansion.marginTiles"}) {
            world(live, Kind.LIVE, Target.NONE);
        }
        world("grid.metersPerColumn", Kind.REINIT, Target.WORLD);
        world("seed", Kind.REINIT, Target.WORLD);
        world("subsurface.levels", Kind.REINIT, Target.WORLD);
        world("geology.datum", Kind.RELOAD, Target.WORLD_INPUTS);
        world("terrain.preset", Kind.RELOAD, Target.WORLD_INPUTS);
    }

    @Test
    void reopenCompatibilityFollowsTheSameRules() {
        assertEquals(ConfigChanges.Kind.HOT, ConfigChanges.volcanoKind("dikes.shearModulusPa"));
        assertEquals(ConfigChanges.Kind.HOT, ConfigChanges.volcanoKind("edifice.material"));
        assertEquals(ConfigChanges.Kind.REINIT, ConfigChanges.volcanoKind("magma.chamber.initialSilicaWt"));
        assertEquals(ConfigChanges.Kind.REINIT, ConfigChanges.worldKind("grid.solverSpacing"));
    }

    @Test
    void messagesNameTheConsequence() {
        String m = ConfigImpact.volcano("magma.chamber.initialWaterWt").message("Kilauea");
        assertTrue(m.startsWith("Restarts Kilauea"), m);
        assertTrue(ConfigImpact.volcano("tephra.cellSizeM").message("Kilauea").contains("ash"));
        assertEquals("Applies at once; the simulation carries on.", ConfigImpact.volcano("dikes.shearModulusPa").message("Kilauea"));
    }

    @Test
    void addingAndRemovingAVolcanoReadAsSentences() {
        assertEquals("Adds Ruapehu after a short pause; everything already simulated carries on.",
                ConfigImpact.volcanoAdded().message("Ruapehu"));
        String removed = ConfigImpact.volcanoRemoved().message("Ruapehu");
        assertTrue(removed.startsWith("Removes Ruapehu: its magma system"), removed);
        assertEquals(ConfigImpact.volcanoRemoved(), ConfigImpact.volcano("*"));
    }

    /** Every template, for every target, with and without a reason: a plain sentence with no template seams. */
    @Test
    void everyMessageIsAWellFormedSentence() {
        java.util.List<ConfigImpact.Impact> all = new java.util.ArrayList<>();
        all.add(ConfigImpact.Impact.LIVE);
        for (Kind k : new Kind[] {Kind.RELOAD, Kind.REINIT}) {
            for (Target t : Target.values()) {
                if (t == Target.NONE) continue;
                for (String reason : new String[] {"geology", ConfigImpact.ADDED, ConfigImpact.REMOVED, "an initial condition of the chamber"}) {
                    all.add(new ConfigImpact.Impact(k, t, reason));
                }
            }
        }
        for (ConfigImpact.Impact i : all) {
            for (String name : new String[] {"Kilauea", null}) {
                String m = i.message(name);
                String what = i + " / " + name + ": " + m;
                assertTrue(Character.isUpperCase(m.charAt(0)), what);
                assertTrue(m.endsWith("."), what);
                assertTrue(!m.contains("null") && !m.contains("()") && !m.contains("  ") && !m.contains("'s a ") && !m.contains("'s an "),
                        what);
            }
        }
    }
}
