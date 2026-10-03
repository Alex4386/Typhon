package me.alex4386.typhon.engine.alert;

/**
 * Volcano status, ordered from least to most active. Mirrors the levels of the legacy Typhon plugin
 * so hosts and the web UI can keep their icons and wording.
 */
public enum AlertLevel {
    EXTINCT,
    DORMANT,
    MINOR_ACTIVITY,
    MAJOR_ACTIVITY,
    ERUPTION_IMMINENT,
    ERUPTING;

    public boolean isAbove(AlertLevel other) {
        return compareTo(other) > 0;
    }

    public AlertLevel lower() {
        return this == EXTINCT ? EXTINCT : values()[ordinal() - 1];
    }

    public static AlertLevel max(AlertLevel a, AlertLevel b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
