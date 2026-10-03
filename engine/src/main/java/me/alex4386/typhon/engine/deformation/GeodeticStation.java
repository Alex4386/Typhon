package me.alex4386.typhon.engine.deformation;

import java.util.Objects;

/** A virtual GNSS receiver + tiltmeter at world column ({@code x}, {@code z}). */
public record GeodeticStation(String name, int x, int z) {
    public GeodeticStation {
        Objects.requireNonNull(name, "name");
    }
}
