package me.alex4386.typhon.engine.tephra;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.HistoricalEvent;

/** Events emitted by {@link TephraSubsystem}. */
public final class TephraEvents {
    private TephraEvents() {}

    /**
     * A bomb left the vent. Hosts can render it (falling block, block display, particles) by
     * integrating the same equations ({@link Ballistics}) from {@code start} with {@code velocity} and
     * {@code dragFactor}, or simply interpolate towards {@code predictedLanding} over
     * {@code expectedFlightSeconds}.
     *
     * @param start launch point (m)
     * @param velocity launch velocity (m/s, engine axes)
     * @param dragFactor k in a = g − k|v−w|(v−w), 1/m
     * @param predictedLanding where the bomb lands if the terrain does not change (m)
     */
    public record BombLaunched(
            double time,
            String source,
            long bombId,
            Point3 start,
            Vec3d velocity,
            double diameter,
            double dragFactor,
            double expectedFlightSeconds,
            Point3 predictedLanding)
            implements EngineEvent {}

    /**
     * A bomb hit the ground.
     *
     * @param position impact point on the ground (m)
     * @param energyJoules kinetic energy at impact
     * @param craterRadius crater radius in blocks (0 if no crater was dug)
     */
    public record BombLanded(
            double time,
            String source,
            long bombId,
            Point3 position,
            double impactSpeed,
            double energyJoules,
            double diameter,
            double craterRadius)
            implements EngineEvent {}

    /**
     * Ash falling over a square region. Hosts use it for visibility, fog, darkness, sounds and particles.
     *
     * @param center region centre (m; y = 0, hosts use the local surface)
     * @param halfSize half the region's side in columns
     * @param fallRate deposition rate, kg/m²/s
     * @param airborneLoad ash suspended above the region, kg/m²
     */
    public record AshFall(double time, String source, Point3 center, int halfSize, double fallRate, double airborneLoad)
            implements EngineEvent {}

    /** A lightning flash inside the ash plume; hosts may strike vanilla lightning here. */
    public record VolcanicLightning(double time, String source, Point3 position) implements EngineEvent {}

    /**
     * The eruption column above a vent, emitted periodically while an explosive phase is active.
     *
     * @param base top of the vent (m)
     * @param topY plume top height (world block y)
     * @param radius approximate umbrella radius in blocks
     */
    public record PlumeColumn(double time, String source, Point3 base, int topY, double radius, double massEruptionRate)
            implements EngineEvent {}

    /** An explosive phase started or ended. */
    public record ExplosivePhaseChanged(double time, String source, boolean active, double massEruptionRate)
            implements HistoricalEvent {}
}
