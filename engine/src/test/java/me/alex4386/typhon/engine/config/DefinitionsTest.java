package me.alex4386.typhon.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.save.SaveFormat;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

class DefinitionsTest {
    static final String WORLD = """
            name: twin
            seed: 42
            baseStepMs: 50
            grid: {metersPerColumn: 4, solverSpacing: 16}
            scaling: {plumeMetersPerBlock: 100, dormantTimeCompression: 5000, eruptiveTimeCompression: 20}
            seaLevel: .nan
            climate:
              rainfallMmPerHour: 2
              wind: {speed: 8, bearingDeg: 90, variability: 0.2}
            geology:
              datum: -2000
              basement:
                - {material: granite, top: -800, porosity: 0.01}
                - {material: sediment, top: -300, porosity: 0.15}
              edificeMaterial: basalt
            terrain: {source: preset, preset: twin}
            lava: {coolingScale: 2}
            """;

    static final String VOLCANO = """
            name: West cone
            vents:
              - {id: summit, kind: crater, x: -30, y: 70, z: 0, radius: 5}
              - {id: rift, kind: fissure, x: -10, y: 55, z: 8, angleDeg: 30, length: 16}
            timeCompression: {eruptive: 10}
            ballisticFraction: 0.1
            magma:
              chamber:
                center: {x: -30, y: -20, z: 0}
                volume: 1.0e9
                supplyRate: 0.5
                initialSilicaWt: 51
              conduit: {initialOpenness: 1.0}
            dikes: {enabled: true, conduitSealing: 0.7}
            geothermal: {center: {x: -30, y: 70, z: 0}, maxGeysers: 3, alterableSurfaces: ["minecraft:stone"]}
            massFlows: {pdc: {frictionCoefficient: 0.2}}
            deformation: {enabled: false}
            tephra: {bombMedianDiameter: 0.4}
            edifice: {material: basalt}
            """;

    static WorldDefinition world() {
        return WorldDefinition.parse(Yaml.parse("world.yaml", WORLD));
    }

    static VolcanoDefinition volcano(String id, String text) {
        return VolcanoDefinition.parse(id, Yaml.parse("volcanoes/" + id + ".yaml", text));
    }

    @Test
    void worldDefinitionMapsOntoEngineObjects() {
        WorldDefinition w = world();
        assertEquals("twin", w.name());
        assertEquals(42, w.seed());
        assertEquals(50_000, w.baseStepMicros());
        assertEquals(4, w.spec().metersPerColumn());
        assertEquals(16, w.spec().solverSpacing());
        assertEquals(4, w.scaling().metersPerBlock());
        // the fixture still has scaling.*TimeCompression: retired keys load and are ignored
        assertTrue(Double.isNaN(w.spec().seaLevelZ()));
        assertEquals(2, w.spec().basement().size());
        assertEquals("basalt", w.spec().edificeMaterial());
        assertEquals(2, w.climate().rainfallMmPerHour());
        assertTrue(w.climate().hasWind());
        assertEquals("preset", w.terrain().get("source"));
        assertEquals(2, w.lava().coolingScale());
    }

    @Test
    void volcanoDefinitionMapsOntoEngineObjects() {
        VolcanoDefinition v = volcano("west", VOLCANO);
        assertEquals("West cone", v.name());
        assertTrue(v.active());
        assertEquals(2, v.vents().size());
        assertEquals(VentKind.FISSURE, v.vents().get(1).kind());
        assertEquals(Math.toRadians(30), v.vents().get(1).fissureAngleRad(), 1e-12);
        assertEquals(new BlockPos(-30, -20, 0), v.chamber().center());
        assertEquals(1.0e9, v.chamber().volume());
        assertEquals(0.5, v.chamber().supplyRate());
        assertEquals(1.0, v.chamber().conduit().initialOpenness());
        assertEquals(0.7, v.dikes().conduitSealing);
        assertEquals(3, v.geothermal().maxGeysers);
        assertTrue(v.geothermal().alterableSurfaces.contains(BlockId.minecraft("stone")));
        assertEquals(new BlockPos(-30, 70, 0), v.geothermalCenter());
        assertEquals(0.2, v.pdc().frictionCoefficient);
        assertFalse(v.deformation());
        assertEquals(0.4, v.tephra().bombMedianDiameter);
        // the fixture's retired timeCompression section is ignored: one physical clock
        assertEquals(world().scaling(), v.scaling(world().scaling()));
        assertEquals("basalt", v.edificeMaterial());
    }

    @Test
    void minimalVolcanoGetsDefaults() {
        VolcanoDefinition v = volcano("solo", "vents: [{id: main, x: 0, y: 64, z: 0}]");
        assertEquals("solo", v.name());
        assertNotNull(v.dikes());
        assertNotNull(v.geothermal());
        assertNull(v.geothermalCenter());
        assertNotNull(v.pdc());
        assertTrue(v.deformation());
        assertEquals(VentKind.CRATER, v.primaryVent().kind());
    }

    @Test
    void definitionsRoundTripThroughYaml() {
        WorldDefinition w = world();
        WorldDefinition w2 = WorldDefinition.parse(Yaml.parse("world.yaml", Yaml.dump(w.toTree())));
        assertEquals(json(w.toTree()), json(w2.toTree()));

        VolcanoDefinition v = volcano("west", VOLCANO);
        VolcanoDefinition v2 = VolcanoDefinition.parse("west", Yaml.parse("west.yaml", Yaml.dump(v.toTree())));
        assertEquals(json(v.toTree()), json(v2.toTree()));
    }

    private static String json(Object tree) {
        return SaveFormat.gson().toJson(tree);
    }

    // ── Validation errors ──

    private static String error(String id, String text) {
        return assertThrows(ConfigException.class, () -> volcano(id, text)).getMessage();
    }

    @Test
    void typoNamesFileKeyPathAndValidKeys() {
        String msg = error("v", """
                vents: [{id: a, x: 0, y: 0, z: 0}]
                magma: {chamber: {volumee: 1.0e9}}
                """);
        assertTrue(msg.startsWith("volcanoes/v.yaml: magma.chamber.volumee: unknown key"), msg);
        assertTrue(msg.contains("volume"), "lists the valid keys: " + msg);
    }

    @Test
    void wrongTypesAreReportedWithThePath() {
        String msg = error("v", """
                vents: [{id: a, x: 0, y: 0, z: 0}]
                magma: {chamber: {volume: big}}
                """);
        assertTrue(msg.contains("magma.chamber.volume: expected a number"), msg);
        String vent = error("v", "vents: [{id: a, x: 1.5, y: 0, z: 0}]");
        assertTrue(vent.contains("vents[0].x: expected an integer"), vent);
    }

    @Test
    void scaledValuesCannotBeSetPerVolcano() {
        String msg = error("v", """
                vents: [{id: a, x: 0, y: 0, z: 0}]
                dikes: {metersPerBlock: 2}
                """);
        assertTrue(msg.contains("dikes.metersPerBlock: is derived from the world scaling"), msg);
    }

    @Test
    void structuralErrors() {
        assertTrue(error("v", "name: x").contains("vents: at least one vent is required"));
        assertTrue(error("v", "id: other\nvents: [{id: a}]").contains("does not match the file name"));
        assertTrue(error("v", "vents: [{id: a}, {id: a}]").contains("duplicate vent id"));
        assertTrue(error("v", "vents: [{id: a, kind: cone}]").contains("expected crater or fissure"));
        assertTrue(error("v", "vents: [{id: a}]\nedifice: {material: cheese}").contains("unknown material 'cheese'"));
        assertTrue(error("v", "vents: [{id: a}]\nmagma: {conduit: {initialOpenness: 2}}").contains("invalid values"));
        assertTrue(error("v", "vents: [{id: a}]\nvents: []").contains("invalid YAML"), "duplicate keys are rejected");
        assertTrue(error("v", "vents: [{id: a}\n").contains("invalid YAML"));
        String world = assertThrows(ConfigException.class,
                () -> WorldDefinition.parse(Yaml.parse("world.yaml", "name: w\ngeology: {basement: [{material: lava, top: 0}]}")))
                .getMessage();
        assertTrue(world.contains("world.yaml: geology.basement[0]: Unknown material 'lava'"), world);
        String scale = assertThrows(ConfigException.class,
                () -> WorldDefinition.parse(Yaml.parse("world.yaml", "name: w\nscaling: {metersPerBlock: 3}")))
                .getMessage();
        assertTrue(scale.contains("grid.metersPerColumn"), scale);
    }
}
