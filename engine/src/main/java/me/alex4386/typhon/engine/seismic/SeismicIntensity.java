package me.alex4386.typhon.engine.seismic;

import me.alex4386.typhon.engine.math.Point3;

/**
 * Ground-shaking estimates at a point, for hosts that scale tremor effects per viewer.
 *
 * <p>Amplitude decays with hypocentral distance as {@code (r₀ / r)^1.5} (body-wave geometric
 * spreading plus attenuation), with {@code r₀ = 200} m as the near-field saturation distance.
 *
 * <p>References: McNutt (2005), Annu. Rev. Earth Planet. Sci. 33:461-491 (volcano seismology). See {@code docs/references.md}.
 */
public final class SeismicIntensity {
    /** Near-field saturation distance (m). */
    public static final double NEAR_FIELD_DISTANCE = 200;
    public static final double DECAY_EXPONENT = 1.5;

    /** log10 amplitude that maps to intensity 0 and the span (in log10 units) up to intensity 1. */
    private static final double LOG_FLOOR = -0.5;
    private static final double LOG_SPAN = 4.0;

    private SeismicIntensity() {}

    /** Relative ground-motion amplitude (counts) at {@code point} for a source of the given magnitude. */
    public static double amplitudeAt(double magnitude, Point3 hypocenter, Point3 point) {
        double dx = point.x() - hypocenter.x();
        double dy = point.y() - hypocenter.y();
        double dz = point.z() - hypocenter.z();
        double distance = Math.max(NEAR_FIELD_DISTANCE, Math.sqrt(dx * dx + dy * dy + dz * dz));
        return Math.pow(10, magnitude) * Math.pow(NEAR_FIELD_DISTANCE / distance, DECAY_EXPONENT);
    }

    /**
     * Perceived shaking in {@code [0, 1]}: 0 = imperceptible, 1 = violent. An M0 event is barely
     * felt next to its hypocentre; an M4 event is violent within the near field.
     */
    public static double intensityAt(double magnitude, Point3 hypocenter, Point3 point) {
        double log = Math.log10(amplitudeAt(magnitude, hypocenter, point));
        double x = (log - LOG_FLOOR) / LOG_SPAN;
        return x < 0 ? 0 : Math.min(1, x);
    }

    public static double intensityAt(SeismicEvent event, Point3 point) {
        return intensityAt(event.magnitude(), event.hypocenter(), point);
    }
}
