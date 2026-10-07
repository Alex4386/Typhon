package me.alex4386.typhon.engine.lava;

import java.util.List;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;

/** Events emitted by {@link LavaFlow}. */
public final class LavaEvents {
    private LavaEvents() {}

    /**
     * Periodic flow telemetry.
     *
     * @param front active lava column farthest from any source
     * @param lengthM horizontal distance from the nearest source to {@code front}, real metres
     * @param volumeM3 molten volume, real m³
     */
    public record LavaFlowFront(double time, Point3 front, double lengthM, int activeCells, double volumeM3)
            implements EngineEvent {}

    /**
     * Lava that turned to rock during the last {@code intervalSeconds} (aggregated; see
     * {@link LavaConfig#eventPeriodSeconds()}).
     *
     * @param cells columns that solidified (a column may count more than once)
     * @param volumeM3 real volume solidified
     */
    public record LavaSolidified(double time, double intervalSeconds, int cells, double volumeM3)
            implements EngineEvent {}

    /**
     * Lava in contact with water in one zone (a 16×16 chunk), aggregated over
     * {@link LavaConfig#eventPeriodSeconds()}. Emitted while the zone holds submerged molten lava.
     *
     * @param pos submerged molten column with the strongest lava inflow (where hosts should centre
     *     steam plumes and spatter)
     * @param columns submerged molten columns in the zone at the end of the interval
     * @param moltenVolumeM3 molten lava in those columns
     * @param inflowM3PerS mean rate at which lava flowed into submerged columns
     * @param powerMW mean heat released into the water over the interval
     * @param steamKgPerS water boiled off by that power (upper bound: all heat goes into steam)
     * @param littoralExplosion some column's entry flux exceeded
     *     {@link LavaConfig#littoralExplosionFluxM3s()}: hosts may render steam blasts and spatter
     */
    public record LavaOceanEntry(double time, double intervalSeconds, Point3 pos, int columns, double moltenVolumeM3,
            double inflowM3PerS, double powerMW, double steamKgPerS, boolean littoralExplosion) implements EngineEvent {}

    /** Drained lava tubes left hollow this step (see {@link LavaFlow#tubes()}). */
    public record LavaTubesFormed(double time, List<LavaTube> tubes) implements EngineEvent {
        public LavaTubesFormed {
            tubes = List.copyOf(tubes);
        }
    }

    /** The flow reached chunks the host has not sent terrain for; they act as walls until it does. */
    public record TerrainNeeded(double time, List<ChunkCoord> chunks) implements EngineEvent {
        public TerrainNeeded {
            chunks = List.copyOf(chunks);
        }
    }

    public record ChunkCoord(int x, int z) {}
}
