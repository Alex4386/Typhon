package me.alex4386.typhon.engine.tephra;

import java.util.Objects;
import me.alex4386.typhon.engine.command.EngineCommand;

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
