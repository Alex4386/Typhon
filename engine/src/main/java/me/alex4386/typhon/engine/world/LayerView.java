package me.alex4386.typhon.engine.world;

/**
 * One layer of a column, as returned by queries. Elevations are real metres.
 *
 * @param porosity 0–1
 * @param voidFraction sub-cell void fraction, 0–1
 * @param welding solid/welding fraction, 0–1
 */
public record LayerView(double bottom, double top, short material, int unit, double porosity, double voidFraction,
        double welding, int flags) {
    public double thickness() {
        return top - bottom;
    }

    public Material materialInfo() {
        return MaterialTable.get(material);
    }

    public boolean loose() {
        return (flags & LayerFlags.LOOSE) != 0;
    }
}
