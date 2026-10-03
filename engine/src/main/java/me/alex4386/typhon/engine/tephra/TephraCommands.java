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

    public record StopExplosivePhase(String target) implements EngineCommand {
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
     * @param maxBombs cap on the number of bombs in this salvo
     */
    public record LaunchSalvo(String target, VentSite vent, double ballisticMassKg, double exitSpeed,
            double zenithMeanDeg, double zenithSigmaDeg, double silicaWt, int maxBombs) implements EngineCommand {
        public LaunchSalvo {
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
