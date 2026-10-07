package me.alex4386.typhon.engine.deformation;

import java.util.List;
import me.alex4386.typhon.engine.output.EngineEvent;

public final class DeformationEvents {
    private DeformationEvents() {}

    /**
     * Periodic geodetic sample for dashboards.
     *
     * @param chamberVolumeChangeM3 Mogi source volume change relative to zero overpressure
     * @param summitUpliftM vertical displacement above the chamber (m)
     */
    public record DeformationSample(double time, String volcanoId, double chamberVolumeChangeM3, double summitUpliftM,
            List<StationReading> stations) implements EngineEvent {
        public DeformationSample {
            stations = List.copyOf(stations);
        }
    }

    /** The uplift field of the ground changed: columns raised and lowered by at least 5 mm since the last write. */
    public record GroundDeformed(double time, String volcanoId, int columnsRaised, int columnsLowered)
            implements EngineEvent {}
}
