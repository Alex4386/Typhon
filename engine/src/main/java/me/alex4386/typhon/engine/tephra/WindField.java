package me.alex4386.typhon.engine.tephra;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.random.SimRandom;

/**
 * Horizontal wind, uniform in space, varying slowly and deterministically in time.
 *
 * <p>Direction is the bearing the wind blows <em>towards</em>, in radians from +X towards +Z. With
 * variability v ∈ [0, 1] the direction swings by up to ±0.6·v rad (10-minute period) and the speed by
 * ±30%·v (~28-minute period); the phases are drawn once from the simulation random stream when the
 * wind is set, so the variation is reproducible.
 */
public final class WindField {
    static final double DIRECTION_PERIOD_SECONDS = 600;
    static final double SPEED_PERIOD_SECONDS = 1700;

    private double speed;
    private double directionRad;
    private double variability;
    private double directionPhase;
    private double speedPhase;

    public WindField(double speed, double directionRad, double variability) {
        set(speed, directionRad, variability, 0, 0);
    }

    void set(double speed, double directionRad, double variability, double directionPhase, double speedPhase) {
        if (!(speed >= 0)) throw new IllegalArgumentException("speed must be >= 0");
        if (!(variability >= 0 && variability <= 1)) throw new IllegalArgumentException("variability must be in [0, 1]");
        this.speed = speed;
        this.directionRad = directionRad;
        this.variability = variability;
        this.directionPhase = directionPhase;
        this.speedPhase = speedPhase;
    }

    void set(double speed, double directionRad, double variability, SimRandom random) {
        set(speed, directionRad, variability, random.nextDouble(0, 2 * Math.PI), random.nextDouble(0, 2 * Math.PI));
    }

    public double baseSpeed() {
        return speed;
    }

    public double baseDirectionRad() {
        return directionRad;
    }

    public double variability() {
        return variability;
    }

    /** Wind velocity (m/s, y = 0) at simulation time {@code t} (seconds). */
    public Vec3d at(double t) {
        if (speed == 0) return Vec3d.ZERO;
        double direction = directionRad;
        double s = speed;
        if (variability > 0) {
            direction += variability * 0.6 * StrictMath.sin(2 * Math.PI * t / DIRECTION_PERIOD_SECONDS + directionPhase);
            s *= 1 + variability * 0.3 * StrictMath.sin(2 * Math.PI * t / SPEED_PERIOD_SECONDS + speedPhase);
        }
        return new Vec3d(s * StrictMath.cos(direction), 0, s * StrictMath.sin(direction));
    }

    void save(JsonObject out) {
        out.addProperty("speed", speed);
        out.addProperty("direction", directionRad);
        out.addProperty("variability", variability);
        out.addProperty("directionPhase", directionPhase);
        out.addProperty("speedPhase", speedPhase);
    }

    void load(JsonObject in) {
        set(
                in.get("speed").getAsDouble(),
                in.get("direction").getAsDouble(),
                in.get("variability").getAsDouble(),
                in.get("directionPhase").getAsDouble(),
                in.get("speedPhase").getAsDouble());
    }
}
