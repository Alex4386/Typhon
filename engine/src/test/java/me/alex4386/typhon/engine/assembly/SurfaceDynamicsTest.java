package me.alex4386.typhon.engine.assembly;

import me.alex4386.typhon.engine.testing.TestConduits;
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
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.seismic.SeismicEventType;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.sim.SimTime;
import me.alex4386.typhon.engine.terrain.GroundColumn;
import me.alex4386.typhon.engine.terrain.GroundImport;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import org.junit.jupiter.api.Test;

/** Discrete explosions and magma–water interaction at the surface. */
class SurfaceDynamicsTest {
    private static final double SEA_LEVEL = 0;
    /** Column width (m): the default 10 m grid. */
    private static final double COLUMN_M = 10;

    /** Flat sedimentary sea floor at {@code floorZ} (m) under water up to {@link #SEA_LEVEL}, over ±800 m. */
    static GroundImport seaFloor(double floorZ) {
        List<GroundColumn> columns = new ArrayList<>();
        for (int x = -80; x < 80; x++) {
            for (int z = -80; z < 80; z++) {
                columns.add(new GroundColumn(x, z, floorZ, SEA_LEVEL, MaterialTable.SEDIMENT));
            }
        }
        return new GroundImport(columns);
    }

    /** Surtsey-like alkali basalt erupting at once through its molten conduit. */
    static MagmaChamberConfig submarineBasalt(String id, Point3 vent) {
        return MagmaChamberConfig.builder(id, vent.offset(0, -3000, 0))
                .conduit(TestConduits.molten(12))
                .volume(5e8).lithostaticDepth(3000).conduitRadius(2)
                .tensileStrengthMPa(12)
                .supplyRate(2).supplyVariability(0)
                .initialSilicaWt(46.5).rechargeSilicaWt(46.5)
                .initialWaterWt(0.7).rechargeWaterWt(0.7)
                .initialTemperatureC(1170).rechargeTemperatureC(1180)
                .initialOverpressureMPa(12.1)
                .build();
    }

    record World(Engine engine, TerrainModel terrain, LavaFlow lava, VolcanoSystem volcano) {}

    static World world(String id, GroundImport ground, VentSite vent, MagmaChamberConfig chamber) {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder(id, List.of(vent), terrain, lava)
                .chamber(chamber)
                .dikesEnabled(false)
                .build();
        Engine.Builder builder = Engine.builder(7).adaptive(Engine.DEFAULT_MAX_STEP_SECONDS).add(terrain);
        volcano.addTo(builder).add(lava);
        Engine engine = builder.build();
        engine.submit(ground);
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
        VentSite vent = VolcanoSystemTest.CRATER;
        MagmaChamberConfig chamber = MagmaChamberConfig.builder("test", new Point3(0, -3000, 0))
                .volume(5e7).lithostaticDepth(3000).conduitRadius(0.8)
                .tensileStrengthMPa(8)
                .supplyRate(0.002).supplyVariability(0)
                .initialSilicaWt(50).rechargeSilicaWt(50)
                .initialWaterWt(2.7).rechargeWaterWt(2.7)
                .initialCo2Wt(0.3).rechargeCo2Wt(0.3)
                .initialTemperatureC(1140).rechargeTemperatureC(1150)
                .initialOverpressureMPa(0)
                .conduit(ConduitConfig.DEFAULT.withInitialOpenness(1))
                .build();
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder("test", List.of(vent), terrain, lava)
                .chamber(chamber)
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
        VentSite vent = VentSite.crater("surtur", new Point3(5, -16, 5), 15);
        World w = world("sea", seaFloor(-16), vent, submarineBasalt("sea", vent.position()));
        // the tuff ring needs minutes to rise above the sea
        List<EngineFrame> frames = new ArrayList<>();
        List<Double> emitted = new ArrayList<>(); // lava volume emitted by the end of each frame
        long endMicros = w.engine().timeMicros() + SimTime.micros(40 * 60);
        while (w.engine().timeMicros() < endMicros) {
            frames.add(w.engine().step());
            emitted.add(w.lava().emittedVolume());
        }

        List<PhreatomagmaticChanged> changes = events(frames, PhreatomagmaticChanged.class);
        assertFalse(changes.isEmpty());
        assertTrue(changes.get(0).active(), "16 m of water over the vent: magma–water explosions");
        assertTrue(events(frames, ExplosiveBurst.class).stream().anyMatch(b -> b.kind() == BurstKind.SURTSEYAN_JET));
        assertFalse(events(frames, SurfaceEvents.PhreatomagmaticSteam.class).isEmpty());

        double sealedAt = changes.stream().filter(c -> !c.active()).mapToDouble(PhreatomagmaticChanged::time).findFirst()
                .orElseThrow(() -> new AssertionError("the tuff ring should isolate the vent"));
        // Little lava while the sea floods the vent (clasts and lava are quenched); effusion once it is sealed off.
        int sealedFrame = 0;
        while (frames.get(sealedFrame).time() < sealedAt) sealedFrame++;
        double atSeal = sealedFrame == 0 ? 0 : emitted.get(sealedFrame - 1);
        double end = frames.get(frames.size() - 1).time();
        double lavaBefore = atSeal / sealedAt;
        double lavaAfter = (emitted.get(emitted.size() - 1) - atSeal) / (end - sealedAt);
        assertTrue(lavaBefore < 0.3 * lavaAfter, "lava emitted (m³/s) " + lavaBefore + " → " + lavaAfter);
        // (the sea floods the crater again now and then, and the vent may be fountaining at the end)
        assertTrue(lavaAfter > 0, "lava flows once the ring is sealed");
        assertTrue(w.lava().emittedVolume() > 0);

        // The rim of the ring now stands above the sea.
        WorldModel world = w.terrain().world();
        int rimAbove = 0;
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                double r = Math.hypot((x + 0.5) * COLUMN_M - vent.position().x(), (z + 0.5) * COLUMN_M - vent.position().z());
                if (r > vent.craterRadiusM() && r <= vent.craterRadiusM() + 60 && world.surfaceZ(x, z) >= SEA_LEVEL) rimAbove++;
            }
        }
        assertTrue(rimAbove > 0, "a tuff ring emerged");
    }

    @Test
    void deepSubmarineVentErupsQuietlyAsPillowLava() {
        VentSite vent = VentSite.crater("deep", new Point3(5, -160, 5), 15);
        World w = world("deep", seaFloor(-160), vent, submarineBasalt("deep", vent.position()));
        List<EngineFrame> frames = w.engine().runFor(40 * 60);

        assertTrue(events(frames, PhreatomagmaticChanged.class).isEmpty(),
                "160 m of water suppresses explosive steam expansion");
        assertEquals(0, w.volcano().coupler().partition().waterFragmentedMassFlux(), "no magma–water fragmentation");
        assertTrue(events(frames, ExplosiveBurst.class).stream().noneMatch(b -> b.kind() == BurstKind.SURTSEYAN_JET));
        assertTrue(w.volcano().coupler().effusing());
        assertTrue(w.lava().emittedVolume() > 0);
    }
}
