package me.alex4386.typhon.engine.deformation;

/**
 * One sample of a {@link GeodeticStation}.
 *
 * @param displacement GNSS-like displacement (m) relative to the unstressed reference
 * @param tiltEastMicroRad ground tilt, positive when the ground rises towards the east (µrad)
 * @param tiltNorthMicroRad ground tilt, positive when the ground rises towards the north (µrad)
 */
public record StationReading(String name, Displacement displacement, double tiltEastMicroRad,
        double tiltNorthMicroRad) {}
