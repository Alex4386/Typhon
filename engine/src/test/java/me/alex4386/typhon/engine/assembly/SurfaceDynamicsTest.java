package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.alert.EruptionStyle;
import me.alex4386.typhon.engine.assembly.SurfaceEvents.BurstKind;
import me.alex4386.typhon.engine.assembly.SurfaceEvents.ExplosiveBurst;
import me.alex4386.typhon.engine.assembly.SurfaceEvents.PhreatomagmaticChanged;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.ConduitConfig;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.seismic.SeismicEventType;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

/** Discrete explosions and magma–water interaction at the surface. */
class SurfaceDynamicsTest {
    private static final int SEA_LEVEL = 60;
    private static final BlockId SAND = BlockId.minecraft("sand");

    /** Flat sea floor at {@code floorY} under water up to {@link #SEA_LEVEL}. */
    static TerrainSnapshot seaFloor(int floorY) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -5; cx < 5; cx++) {
            for (int cz = -5; cz < 5; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        chunk.set(x, z, new TerrainColumn(floorY, SEA_LEVEL, SAND));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    /** Surtsey-like alkali basalt erupting at once. */
    static MagmaChamberConfig submarineBasalt(String id, BlockPos vent) {
        return MagmaChamberConfig.builder(id, new BlockPos(vent.x(), Math.max(-56, vent.y() - 48), vent.z()))
                .volume(5e8).lithostaticDepth(3000).conduitRadius(2)
                .tensileStrengthMPa(12).eruptionEndOverpressureMPa(1)
                .supplyRate(2).supplyVariability(0)
                .initialSilicaWt(46.5).rechargeSilicaWt(46.5)
                .initialWaterWt(0.7).rechargeWaterWt(0.7)
                .initialTemperatureC(1170).rechargeTemperatureC(1180)
                .initialOverpressureMPa(12.1)
                .build();
    }

    record World(Engine engine, TerrainModel terrain, LavaFlow lava, VolcanoSystem volcano) {}

    static World world(String id, TerrainSnapshot snapshot, VentSite vent, MagmaChamberConfig chamber) {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder(id, List.of(vent), terrain, lava)
                .chamber(chamber)
                .scaling(VolcanoScaling.DEFAULT)
                .dikesEnabled(false)
                .build();
        Engine.Builder builder = Engine.builder(7).add(terrain);
        volcano.addTo(builder).add(lava);
        Engine engine = builder.build();
        engine.submit(snapshot);
        return new World(engine, terrain, lava, volcano);
    }

    static List<EngineFrame> run(Engine engine, int ticks) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.step());
        return frames;
    }

    static <T extends EngineEvent> List<T> events(List<EngineFrame> frames, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EngineFrame f : frames) for (EngineEvent e : f.events()) if (type.isInstance(e)) out.add(type.cast(e));
        return out;
    }

    @Test
    void strombolianBurstsComeWithExplosionQuakesAndBombs() {
        VentSite vent = VentSite.crater("summit", new BlockPos(0, 117, 0), 4);
        MagmaChamberConfig chamber = MagmaChamberConfig.builder("test", new BlockPos(0, 60, 0))
                .volume(5e7).lithostaticDepth(3000).conduitRadius(0.8)
                .tensileStrengthMPa(8).eruptionEndOverpressureMPa(0.5)
                .supplyRate(0.002).supplyVariability(0)
                .initialSilicaWt(50).rechargeSilicaWt(50)
                .initialWaterWt(2.7).rechargeWaterWt(2.7)
                .initialCo2Wt(0.3).rechargeCo2Wt(0.3)
                .initialTemperatureC(1140).rechargeTemperatureC(1150)
                .initialOverpressureMPa(0)
                .conduit(ConduitConfig.DEFAULT.withInitialOpenness(1).withReopenOverpressureMPa(1.5))
                .build();
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder("test", List.of(vent), terrain, lava)
                .chamber(chamber)
                .scaling(VolcanoScaling.DEFAULT)
                .dikesEnabled(false)
                .build();
        Engine.Builder builder = Engine.builder(11).add(terrain);
        volcano.addTo(builder).add(lava);
        Engine engine = builder.build();
        engine.submit(VolcanoSystemTest.cone());
        List<EngineFrame> frames = run(engine, 20 * 60 * 30);

        assertEquals(EruptionStyle.STROMBOLIAN, volcano.alert().suggestedStyle(), "estimated from what the vent does");
        assertFalse(volcano.classifier().isForecast(), "explosions are activity, not a forecast");
        assertFalse(volcano.chamber().erupting(), "chamber gas rising through the open conduit: no lava");
        assertFalse(volcano.coupler().explosive(), "no sustained column");
        List<ExplosiveBurst> bursts = events(frames, ExplosiveBurst.class);
        assertTrue(bursts.size() >= 3, "explosions in half an hour: " + bursts.size());
        assertTrue(bursts.stream().allMatch(b -> b.kind() == BurstKind.STROMBOLIAN));

        List<SeismicEvent> quakes = events(frames, SeismicEvent.class).stream()
                .filter(e -> e.type() == SeismicEventType.EXPLOSION).toList();
        for (ExplosiveBurst burst : bursts) {
            assertTrue(quakes.stream().anyMatch(q -> q.time() >= burst.time() && q.time() <= burst.time() + 2),
                    "every burst has an explosion quake right after it");
        }
        assertEquals(bursts.size(), quakes.size(), "explosion quakes only come from bursts in an open vent");
        assertFalse(events(frames, me.alex4386.typhon.engine.tephra.TephraEvents.BombLaunched.class).isEmpty(),
                "bursts throw bombs");
    }

    @Test
    void shallowSubmarineVentIsSurtseyanUntilItsTuffRingSealsItOff() {
        VentSite vent = VentSite.crater("surtur", new BlockPos(0, 56, 0), 3);
        World w = world("sea", seaFloor(56), vent, submarineBasalt("sea", vent.position()));
        List<EngineFrame> frames = run(w.engine(), 20 * 60 * 5);

        List<PhreatomagmaticChanged> changes = events(frames, PhreatomagmaticChanged.class);
        assertFalse(changes.isEmpty());
        assertTrue(changes.get(0).active(), "16 m of water over the vent: magma–water explosions");
        assertTrue(events(frames, ExplosiveBurst.class).stream().anyMatch(b -> b.kind() == BurstKind.SURTSEYAN_JET));
        assertFalse(events(frames, SurfaceEvents.PhreatomagmaticSteam.class).isEmpty());

        double sealedAt = changes.stream().filter(c -> !c.active()).mapToDouble(PhreatomagmaticChanged::time).findFirst()
                .orElseThrow(() -> new AssertionError("the tuff ring should isolate the vent"));
        // Little lava while the sea floods the vent (clasts and lava are quenched); effusion once it is sealed off.
        double lavaBefore = frames.stream().filter(f -> f.time() < sealedAt)
                .flatMap(f -> f.blockChanges().stream()).filter(c -> c.to().id().path().equals("lava")).count() / sealedAt;
        double end = frames.get(frames.size() - 1).time();
        double lavaAfter = frames.stream().filter(f -> f.time() >= sealedAt)
                .flatMap(f -> f.blockChanges().stream()).filter(c -> c.to().id().path().equals("lava")).count()
                / (end - sealedAt);
        assertTrue(lavaBefore < 0.3 * lavaAfter, "lava block changes per second " + lavaBefore + " → " + lavaAfter);
        assertTrue(w.volcano().coupler().effusing());
        assertTrue(w.lava().emittedVolume() > 0);

        // The rim of the ring now stands above the sea.
        int rimAbove = 0;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > 4 && r <= 6 && w.terrain().column(dx, dz).groundY() >= SEA_LEVEL) rimAbove++;
            }
        }
        assertTrue(rimAbove > 0, "a tuff ring emerged");
    }

    @Test
    void deepSubmarineVentErupsQuietlyAsPillowLava() {
        VentSite vent = VentSite.crater("deep", new BlockPos(0, 20, 0), 3);
        World w = world("deep", seaFloor(20), vent, submarineBasalt("deep", vent.position()));
        List<EngineFrame> frames = run(w.engine(), 20 * 60 * 2);

        assertTrue(events(frames, PhreatomagmaticChanged.class).isEmpty(),
                "160 m of water suppresses explosive steam expansion");
        assertEquals(0, w.volcano().coupler().partition().waterFragmentedMassFlux(), "no magma–water fragmentation");
        assertTrue(events(frames, ExplosiveBurst.class).stream().noneMatch(b -> b.kind() == BurstKind.SURTSEYAN_JET));
        assertTrue(w.volcano().coupler().effusing());
        assertTrue(w.lava().emittedVolume() > 0);
    }
}
