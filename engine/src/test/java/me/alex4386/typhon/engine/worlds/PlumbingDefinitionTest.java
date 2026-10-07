package me.alex4386.typhon.engine.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import me.alex4386.typhon.engine.config.ChamberPlacement;
import me.alex4386.typhon.engine.config.ConfigException;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.plumbing.ConnectionConfig;
import me.alex4386.typhon.engine.magma.plumbing.PlumbingConfig;
import me.alex4386.typhon.engine.save.SaveFormat;
import org.junit.jupiter.api.Test;

/** Multi-chamber plumbing in volcano definitions: parsing, round trip, and what each change needs. */
class PlumbingDefinitionTest {
    private static final double L = 10;

    private static VolcanoDefinition single() {
        var r = new ChamberPlacement.Request("v", 0, 0, 1500, null, null, null, null, null, null, 0.0, null, null);
        return ChamberPlacement.definition("v", r, -130, L);
    }

    /** The single-chamber definition with a deep chamber and a conduit from it to the main one. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> twoChamberTree(double deepVolume, double radius) {
        Map<String, Object> tree = single().toTree();
        Map<String, Object> magma = new LinkedHashMap<>((Map<String, Object>) tree.get("magma"));
        var deep = new ChamberPlacement.Request(null, 0, 0, 4500, deepVolume, null, null, null, null, null, 3.0, null, null);
        List<Object> chambers = new ArrayList<>(List.of(ChamberPlacement.chamberElement("deep", deep, -130, L)));
        magma.put("chambers", chambers);
        magma.put("connections", List.of(ChamberPlacement.connectionElement("deep-main", "deep", "main", false, radius, null)));
        tree.put("magma", magma);
        return tree;
    }

    private static VolcanoDefinition parse(Map<String, Object> tree) {
        return VolcanoDefinition.parse("v", ConfigNode.root("volcanoes/v.yaml", tree), L);
    }

    @Test
    void aDeepChamberAndConduitParseInheritAndRoundTrip() {
        VolcanoDefinition d = parse(twoChamberTree(1e10, 2.0));
        PlumbingConfig p = d.plumbing();
        assertEquals(1, p.chambers().size());
        MagmaChamberConfig deep = p.chamber("deep");
        assertNotNull(deep);
        assertEquals(4500, deep.lithostaticDepth());
        assertEquals(1e10, deep.volume());
        assertEquals(3.0, deep.supplyRate(), "its own deep supply");
        assertEquals(d.chamber().initialSilicaWt(), deep.initialSilicaWt(), "unset magma follows the main chamber");
        assertEquals(0, d.chamber().supplyRate(), "the main chamber is fed from below now");
        ConnectionConfig link = p.connections().get(0);
        assertEquals("deep", link.from());
        assertEquals(MagmaChamberConfig.MAIN, link.to());
        assertEquals(2.0, link.radiusM());
        // export → parse gives the same definition
        VolcanoDefinition again = parse(d.toTree());
        assertEquals(d.toTree(), again.toTree());
        assertEquals(d.plumbing(), again.plumbing());
    }

    @Test
    void aSingleChamberDefinitionIsUnchanged() {
        VolcanoDefinition d = single();
        assertTrue(d.plumbing().isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> magma = (Map<String, Object>) d.toTree().get("magma");
        assertTrue(!magma.containsKey("chambers") && !magma.containsKey("connections"), "no new keys for one chamber");
        @SuppressWarnings("unchecked")
        Map<String, Object> chamber = (Map<String, Object>) magma.get("chamber");
        assertTrue(!chamber.containsKey("chamberId"), "the main chamber's id stays implicit");
    }

    @Test
    void badPlumbingIsRejectedWithItsPlace() {
        Map<String, Object> tree = twoChamberTree(1e10, 2.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> magma = (Map<String, Object>) tree.get("magma");
        magma.put("connections", List.of(ChamberPlacement.connectionElement("x", "nowhere", "main", false, null, null)));
        ConfigException e = assertThrows(ConfigException.class, () -> parse(tree));
        assertTrue(e.getMessage().contains("nowhere"), e.getMessage());
    }

    private static ConfigChanges changes(VolcanoDefinition before, VolcanoDefinition after) {
        Map<String, JsonObject> a = new TreeMap<>();
        a.put("v", SaveFormat.gson().toJsonTree(before.toTree()).getAsJsonObject());
        Map<String, JsonObject> b = new TreeMap<>();
        b.put("v", SaveFormat.gson().toJsonTree(after.toTree()).getAsJsonObject());
        return ConfigChanges.compare(null, null, a, b);
    }

    private static ConfigChanges.Change only(ConfigChanges c) {
        assertEquals(1, c.all().size(), c.all().toString());
        return c.all().get(0);
    }

    @Test
    void eachPlumbingChangeIsClassifiedOnItsOwn() {
        VolcanoDefinition one = single();
        VolcanoDefinition two = parse(twoChamberTree(1e10, 2.0));

        // adding the deep chamber (and its conduit): a reload that keeps every other state
        ConfigChanges added = changes(one, two);
        assertTrue(added.all().stream().anyMatch(c -> c.path().equals("magma.chambers[deep]")
                && c.impact().kind() == ConfigImpact.Kind.RELOAD && c.impact().target() == ConfigImpact.Target.CHAMBER));
        assertTrue(added.all().stream().anyMatch(c -> c.path().equals("magma.connections[deep-main]")
                && c.impact().kind() == ConfigImpact.Kind.RELOAD));

        // a wider conduit: live
        ConfigChanges.Change wider = only(changes(two, parse(twoChamberTree(1e10, 3.0))));
        assertEquals("magma.connections[deep-main].radiusM", wider.path());
        assertEquals(ConfigImpact.Kind.LIVE, wider.impact().kind());

        // resizing the deep chamber resets only it
        ConfigChanges.Change bigger = only(changes(two, parse(twoChamberTree(2e10, 2.0))));
        assertEquals(ConfigImpact.Kind.REINIT, bigger.impact().kind());
        assertEquals("deep", bigger.impact().subject());
        assertEquals(java.util.Set.of("magma:v:deep"), changes(two, parse(twoChamberTree(2e10, 2.0))).reinitSubsystems("v"));
        assertTrue(bigger.impact().message("Ruapehu").startsWith("Restarts Ruapehu's chamber deep from its new settings"),
                bigger.impact().message("Ruapehu"));

        // removing it: only it goes
        ConfigChanges removed = changes(two, one);
        assertTrue(removed.all().stream().anyMatch(c -> c.path().equals("magma.chambers[deep]")
                && c.impact().kind() == ConfigImpact.Kind.REINIT && "deep".equals(c.impact().subject())));
        assertTrue(removed.reinitSubsystems("v").contains("magma:v:deep"));
        assertTrue(!removed.reinitSubsystems("v").contains("magma:v"), "the main chamber keeps its state");
    }
}
