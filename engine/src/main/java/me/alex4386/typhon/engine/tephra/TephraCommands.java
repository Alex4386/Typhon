package me.alex4386.typhon.engine.tephra;

import java.util.Objects;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.volcano.VentSite;

/**
 * Commands accepted by {@link TephraSubsystem}. Each carries the {@code target} subsystem id; commands
 * addressed to another tephra subsystem are ignored.
 */
public final class TephraCommands {
    private TephraCommands() {}

    public record StartExplosivePhase(String target, ExplosivePhase phase) implements EngineCommand {
        public StartExplosivePhase {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(phase, "phase");
        }
    }

    /** Stops the phase of vent {@code ventId}, or every phase when it is {@code null}. */
    public record StopExplosivePhase(String target, String ventId) implements EngineCommand {
        public StopExplosivePhase(String target) {
            this(target, null);
        }

        public StopExplosivePhase {
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * @param speed m/s
     * @param directionRad bearing the wind blows towards, radians from +X towards +Z
     * @param variability 0 (steady) to 1 (gusty, veering)
     */
    public record SetWind(String target, double speed, double directionRad, double variability) implements EngineCommand {
        public SetWind {
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * A salvo of ballistic bombs from a discrete explosion (Strombolian burst, Vulcanian blast,
     * Surtseyan jet). The number of bombs follows the ballistic ejecta mass scaled to the model
     * world; speeds are real and scaled like phase-launched bombs.
     *
     * @param ballisticMassKg real mass of the ballistic ejecta (kg)
     * @param exitSpeed real exit speed (m/s)
     * @param zenithMeanDeg mean launch angle from vertical (0 = straight up; ~40 for cock's-tail jets)
     * @param zenithSigmaDeg spread of the launch angle
     * @param maxBombs cap on the number of tracked bombs in this salvo
     * @param carriesMass whether the salvo lays {@code ballisticMassKg} down itself (the tracked bombs stand for
     *     all of it, each weighted); false when the mass is laid by a {@link ProximalFallout} and the bombs
     *     only show the larger clasts
     */
    public record LaunchSalvo(String target, VentSite vent, double ballisticMassKg, double exitSpeed,
            double zenithMeanDeg, double zenithSigmaDeg, double silicaWt, int maxBombs, boolean carriesMass)
            implements EngineCommand {
        public LaunchSalvo {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(vent, "vent");
        }
    }

    /**
     * Proximal fallout of a discrete explosion: {@code massKg} (real kg) of lapilli-sized clasts
     * (between {@code minSizeM} and {@code maxSizeM}, lognormal around {@code medianSizeM}) thrown out
     * ballistically and deposited around the vent. Too numerous and small to track one by one as
     * bombs, too coarse to be carried by the explosion's ash cloud: they land within a few hundred
     * metres, nearly symmetric about the vent, drifting only slightly with the wind.
     */
    public record ProximalFallout(String target, VentSite vent, double massKg, double exitSpeed, double zenithMeanDeg,
            double zenithSigmaDeg, double medianSizeM, double minSizeM, double maxSizeM) implements EngineCommand {
        public ProximalFallout {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(vent, "vent");
        }
    }

    /** Launches a single bomb with explicit initial conditions (debug tools, scripted events). */
    public record LaunchBomb(String target, Vec3d start, Vec3d velocity, double diameter, double silicaWt)
            implements EngineCommand {
        public LaunchBomb {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(velocity, "velocity");
        }
    }
}
