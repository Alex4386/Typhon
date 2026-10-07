package me.alex4386.typhon.engine.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.deformation.DeformationModel;
import me.alex4386.typhon.engine.deformation.Displacement;
import me.alex4386.typhon.engine.deformation.Mogi;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.plumbing.ConnectionConfig;
import me.alex4386.typhon.engine.magma.plumbing.PlumbingConfig;
import org.junit.jupiter.api.Test;

/** A volcano with a deep and a shallow chamber, assembled from its definition. */
class PlumbingWorldTest {
    /** The test cone with a deep chamber 3 km below its main one, fed from the mantle, joined by a conduit. */
    private static VolcanoDefinition twoChambers(double radius) {
        VolcanoDefinition v = WorldTest.volcano("v", 5, 5, 0);
        MagmaChamberConfig main = v.chamber();
        MagmaChamberConfig deep = main.toBuilder().chamberId("deep").lithostaticDepth(main.lithostaticDepth() + 3000)
                .volume(main.volume() * 5).supplyRate(2).initialOverpressureMPa(6).build();
        ConnectionConfig c = ConnectionConfig.of("deep-main", "deep", MagmaChamberConfig.MAIN, ConnectionConfig.Kind.CONDUIT);
        ConnectionConfig sized = new ConnectionConfig(c.id(), c.from(), c.to(), c.kind(), radius, c.widthM(), c.strikeLengthM(), c.lengthM(),
                true, false, c.stallRateM3PerS(), c.freezeSeconds());
        return v.withPlumbing(new PlumbingConfig(List.of(deep), List.of(sized)));
    }

    @Test
    void theDeepChamberRunsBesideTheMainOneAndFeedsIt() {
        WorldDefinition w = WorldTest.world();
        List<VolcanoDefinition> vs = List.of(twoChambers(1.5));
        World world = World.create(w, vs, WorldTest.terrain(w, vs));
        VolcanoSystem v = world.volcano("v");
        assertEquals(2, v.chambers().size());
        assertTrue(world.engine().hasSubsystem("magma:v:deep"));
        assertTrue(world.engine().hasSubsystem("plumbing:v"));
        world.engine().runFor(120);
        MagmaChamber deep = v.chambers().get("deep");
        assertFalse(deep.erupting(), "a deep chamber never erupts itself");
        assertTrue(deep.transferredOutM3() > 0, "it feeds the main chamber");
        assertEquals(deep.transferredOutM3(), v.chamber().transferredInM3(), 1e-6);
        assertEquals(List.of(), world.configDrift(w, vs), "the running world matches its definitions");
    }

    @Test
    void aWiderConduitIsTakenLive() {
        WorldDefinition w = WorldTest.world();
        List<VolcanoDefinition> vs = List.of(twoChambers(1.5));
        World world = World.create(w, vs, WorldTest.terrain(w, vs));
        world.engine().runFor(10);
        double before = world.volcano("v").chambers().get("deep").overpressureMPa();
        List<VolcanoDefinition> wider = List.of(twoChambers(3.0));
        world.reconfigureLive(w, wider);
        assertEquals(3.0, world.volcano("v").plumbing().connections().get(0).radiusM());
        assertEquals(before, world.volcano("v").chambers().get("deep").overpressureMPa(), "state kept");
        assertEquals(List.of(), world.configDrift(w, wider));
    }

    @Test
    void groundDeformationAddsEveryChambersMogiField() {
        WorldDefinition w = WorldTest.world();
        List<VolcanoDefinition> vs = List.of(twoChambers(1.5));
        World world = World.create(w, vs, WorldTest.terrain(w, vs));
        world.engine().runFor(30);
        VolcanoSystem v = world.volcano("v");
        DeformationModel d = v.deformation();
        MagmaChamber deep = v.chambers().get("deep");
        double x = 125;
        double z = -75;
        Displacement both = d.displacementAt(x, z);
        d.setExtraSources(List::of);
        Displacement mainOnly = d.displacementAt(x, z);
        Displacement deepOnly = Mogi.displacement(
                Mogi.volumeChange(deep.volumeM3() - deep.wallGrowthM3(), deep.overpressureMPa(), d.config().shearModulusPa)
                        + deep.inelasticVolumeChangeM3(),
                deep.config().lithostaticDepth(), x - deep.config().center().x(), -(z - deep.config().center().z()),
                d.config().poissonRatio);
        assertEquals(mainOnly.up() + deepOnly.up(), both.up(), 1e-12);
        assertEquals(mainOnly.east() + deepOnly.east(), both.east(), 1e-12);
        assertTrue(Math.abs(deepOnly.up()) > 0, "the deep chamber deforms the surface too");
    }
}
