package me.alex4386.typhon.engine.world;

import java.util.List;

/**
 * Everything the model knows about one column, bottom layer first.
 *
 * @param surfaceZ ground surface elevation (m), {@code NaN} if the column is unknown
 * @param waterZ standing-water surface elevation (m), {@code NaN} if dry
 * @param uplift accumulated ground deformation (m), applied on top of the layers
 */
public record ColumnProfile(int x, int z, double surfaceZ, double waterZ, double uplift, List<LayerView> layers) {
    public ColumnProfile {
        layers = List.copyOf(layers);
    }

    public boolean known() {
        return !layers.isEmpty();
    }
}
