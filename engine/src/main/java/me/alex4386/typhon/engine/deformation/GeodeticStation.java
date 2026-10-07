package me.alex4386.typhon.engine.deformation;

import java.util.Objects;

/** A virtual GNSS receiver + tiltmeter at horizontal position ({@code x}, {@code z}) (m). */
public record GeodeticStation(String name, double x, double z) {
    public GeodeticStation {
        Objects.requireNonNull(name, "name");
    }
}
