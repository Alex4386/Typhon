package me.alex4386.typhon.engine.assembly;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.HistoricalEvent;

/** Events emitted by {@link VolcanoCoupler} about what happens at the vents. */
public final class SurfaceEvents {
    private SurfaceEvents() {}

    public enum BurstKind {
        /** Gas slug bursting at an open vent. */
        STROMBOLIAN,
        /** Dome plug failing over trapped gas. */
        VULCANIAN,
        /** Cock's-tail jet from magma–water interaction at a submerged vent. */
        SURTSEYAN_JET
    }

    /**
     * A discrete explosion at a vent. Hosts render it (sound, flash, shockwave); the bombs, ash and
     * explosion quake it causes arrive as their own events.
     *
     * @param ejectaMassKg real pyroclast mass (kg)
     * @param gasMassKg real gas/steam mass (kg)
     * @param exitSpeed real exit speed (m/s)
     * @param energyJ kinetic energy of the ejecta (J)
     */
    public record ExplosiveBurst(double time, String volcanoId, BurstKind kind, Point3 vent, double ejectaMassKg,
            double gasMassKg, double exitSpeed, double energyJ) implements EngineEvent {}

    /**
     * Sea or lake water started or stopped reaching an erupting vent. While active the eruption is
     * phreatomagmatic (Surtseyan): magma–water explosions, fine ash, steam, no lava.
     *
     * @param waterDepthM mean real water depth over the vent area (m)
     */
    public record PhreatomagmaticChanged(double time, String volcanoId, boolean active, Point3 vent, double waterDepthM)
            implements HistoricalEvent {}

    /**
     * Periodic steam output of a phreatomagmatic vent (for steam clouds, fog, sounds).
     *
     * @param steamKgPerS real steam production (kg/s)
     */
    public record PhreatomagmaticSteam(double time, String volcanoId, Point3 vent, double steamKgPerS, double waterDepthM)
            implements EngineEvent {}
}
