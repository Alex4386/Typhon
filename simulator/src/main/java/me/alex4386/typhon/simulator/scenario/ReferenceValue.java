package me.alex4386.typhon.simulator.scenario;

import java.util.Locale;

/**
 * One row of a preset's "reference vs model" table: a published value for the real volcano and the
 * run metric it is compared with.
 *
 * <p>Numeric references give a range {@code [low, high]} (equal for a single value; either may be
 * {@code NaN} for open-ended); categorical references give {@code expected} text (e.g. an eruption
 * style). The comparison is a sanity check of order of magnitude and qualitative behaviour, not a
 * calibration target (see the realism-with-scaling principle in {@code engine/README.md}).
 *
 * @param quantity what is compared
 * @param low lower bound of the reference (real units), {@code NaN} = none
 * @param high upper bound of the reference, {@code NaN} = none
 * @param expected expected categorical value, or {@code null} for numeric rows
 * @param unit unit of the numeric value
 * @param source literature source of the reference
 * @param metric the run metric compared against it
 */
public record ReferenceValue(String quantity, double low, double high, String expected, String unit, String source,
        Metric metric) {

    /** Quantities measured on a run (see {@code ReportWriter} for how each is computed). */
    public enum Metric {
        /** Highest initial ground surface in the domain (m a.s.l.). */
        SUMMIT_ELEVATION_M,
        /** Highest final ground surface (m a.s.l.), e.g. whether an island emerged. */
        FINAL_MAX_ELEVATION_M,
        /** Peak dense-rock-equivalent eruption rate (m³/s). */
        PEAK_ERUPTION_RATE_M3S,
        /** Total erupted DRE volume over the run (m³). */
        ERUPTED_VOLUME_M3,
        /** Highest eruption column top (km a.s.l.). */
        PLUME_TOP_KM,
        /** Longest lava flow (m). */
        LONGEST_FLOW_M,
        /** Explosion quakes per simulated hour (Strombolian/Vulcanian explosions). */
        EXPLOSIONS_PER_HOUR,
        /** Farthest ballistic landing from the domain centre (m). */
        MAX_BALLISTIC_RANGE_M,
        /** Geysers formed. */
        GEYSERS,
        /** Eruption style at the end of the run. */
        FINAL_STYLE,
        /** Eruption style seen at any sample during the run (matches if any equals {@code expected}). */
        ANY_STYLE
    }

    public static ReferenceValue range(String quantity, double low, double high, String unit, String source,
            Metric metric) {
        return new ReferenceValue(quantity, low, high, null, unit, source, metric);
    }

    public static ReferenceValue value(String quantity, double value, String unit, String source, Metric metric) {
        return new ReferenceValue(quantity, value, value, null, unit, source, metric);
    }

    public static ReferenceValue category(String quantity, String expected, String source, Metric metric) {
        return new ReferenceValue(quantity, Double.NaN, Double.NaN, expected, "", source, metric);
    }

    public boolean categorical() {
        return expected != null;
    }

    /** The reference as text, e.g. {@code 1–10 m³/s}. */
    public String referenceText() {
        if (categorical()) return expected;
        if (Double.isNaN(low)) return "≤ " + fmt(high) + " " + unit;
        if (Double.isNaN(high)) return "≥ " + fmt(low) + " " + unit;
        if (low == high) return fmt(low) + " " + unit;
        return fmt(low) + "–" + fmt(high) + " " + unit;
    }

    /** Where a model value falls relative to the reference range. */
    public enum Verdict { WITHIN, BELOW, ABOVE, MATCH, MISMATCH, NOT_OBSERVED }

    public Verdict judge(double model) {
        if (Double.isNaN(model)) return Verdict.NOT_OBSERVED;
        if (!Double.isNaN(low) && model < low * (low == high ? 0.9 : 1)) return Verdict.BELOW;
        if (!Double.isNaN(high) && model > high * (low == high ? 1.1 : 1)) return Verdict.ABOVE;
        return Verdict.WITHIN;
    }

    public Verdict judge(String model) {
        if (model == null) return Verdict.NOT_OBSERVED;
        return model.equalsIgnoreCase(expected) ? Verdict.MATCH : Verdict.MISMATCH;
    }

    static String fmt(double v) {
        if (Math.abs(v) >= 1e5 || (Math.abs(v) < 1e-2 && v != 0)) return String.format(Locale.ROOT, "%.3g", v);
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
