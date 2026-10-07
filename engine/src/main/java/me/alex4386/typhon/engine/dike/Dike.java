package me.alex4386.typhon.engine.dike;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.deformation.DikeGeometry;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;

/**
 * One dike: a magma-filled crack rising from the chamber. Horizontal position and depth below the
 * surface are in metres.
 */
public final class Dike {
    final int id;
    final double startTime;
    /** Nucleation point (m). */
    final double startX;
    final double startZ;
    /** Ground elevation above the nucleation point (m), for points beyond the known ground. */
    final double surfaceStartZ;
    /** Depth of the chamber (m): the dike's lower edge. */
    final double chamberDepth;

    DikeStatus status = DikeStatus.PROPAGATING;
    double x;
    double z;
    /** Depth of the upper tip below the surface (m). */
    double depth;
    /** Elevation of the upper tip (m). */
    double tipElevation;
    double opening;
    double strikeLength;
    double volume;
    double speed;
    /** Random-walk heading perturbation (dimensionless slope). */
    double noiseX;
    double noiseZ;
    /** Horizontal unit direction of the last advance (0 when it rose vertically). */
    double travelX;
    double travelZ;
    VentSite fissure;
    /** Deleted by the user: its fissure is no longer a vent; the intrusion stays in the rock. */
    boolean removed;

    Dike(int id, double startTime, double x, double z, double surfaceStartZ, double chamberDepth) {
        this.id = id;
        this.startTime = startTime;
        this.startX = x;
        this.startZ = z;
        this.x = x;
        this.z = z;
        this.surfaceStartZ = surfaceStartZ;
        this.chamberDepth = chamberDepth;
        this.depth = chamberDepth;
        this.tipElevation = surfaceStartZ - chamberDepth;
    }

    public int id() { return id; }
    /** Simulation time (s) at which the dike nucleated. */
    public double startTime() { return startTime; }
    public DikeStatus status() { return status; }
    public boolean propagating() { return status == DikeStatus.PROPAGATING; }
    /** Depth of the upper tip below the surface (m). */
    public double depthM() { return depth; }
    /** Height of the dike from the chamber to its tip (m). */
    public double heightM() { return chamberDepth - depth; }
    public double openingM() { return opening; }
    public double strikeLengthM() { return strikeLength; }
    /** Magma volume intruded so far (m³). */
    public double volumeM3() { return volume; }
    /** Tip speed during the last step (m/s). */
    public double speedMPerS() { return speed; }
    /** Horizontal tip position (m). */
    public double x() { return x; }
    public double z() { return z; }
    /** Nucleation point at the chamber (m). */
    public Point3 origin() { return new Point3(startX, surfaceStartZ - chamberDepth, startZ); }
    /** Upper tip (m). */
    public Point3 tip() { return new Point3(x, tipElevation, z); }
    /** The fissure this dike opened, or {@code null}. */
    public VentSite fissure() { return fissure; }
    /** True once the user deleted the dike: it is kept only as an intrusion (deformation, rock). */
    public boolean removed() { return removed; }

    /** Horizontal distance travelled from the nucleation point (m). */
    public double horizontalOffsetM() {
        return Math.hypot(x - startX, z - startZ);
    }

    /** Geometry for the deformation model. */
    public DikeGeometry geometry(double strikeRad) {
        return new DikeGeometry(x, z, strikeRad, strikeLength, Math.max(0, depth), chamberDepth, opening);
    }

    JsonObject save() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("startTime", startTime);
        o.addProperty("startX", startX);
        o.addProperty("startZ", startZ);
        o.addProperty("surfaceStartZ", surfaceStartZ);
        o.addProperty("chamberDepth", chamberDepth);
        o.addProperty("status", status.name());
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("depth", depth);
        o.addProperty("tipElevation", tipElevation);
        o.addProperty("opening", opening);
        o.addProperty("strikeLength", strikeLength);
        o.addProperty("volume", volume);
        o.addProperty("speed", speed);
        o.addProperty("noiseX", noiseX);
        o.addProperty("noiseZ", noiseZ);
        o.addProperty("travelX", travelX);
        o.addProperty("travelZ", travelZ);
        if (removed) o.addProperty("removed", true);
        if (fissure != null) {
            JsonObject f = new JsonObject();
            f.addProperty("id", fissure.id());
            f.add("position", fissure.position().toJson());
            f.addProperty("angle", fissure.fissureAngleRad());
            f.addProperty("length", fissure.fissureLengthM());
            f.addProperty("halfWidth", fissure.craterRadiusM());
            o.add("fissure", f);
        }
        return o;
    }

    static Dike load(JsonObject o) {
        Dike d = new Dike(
                o.get("id").getAsInt(),
                o.get("startTime").getAsDouble(),
                o.get("startX").getAsDouble(),
                o.get("startZ").getAsDouble(),
                o.get("surfaceStartZ").getAsDouble(),
                o.get("chamberDepth").getAsDouble());
        d.status = DikeStatus.valueOf(o.get("status").getAsString());
        d.x = o.get("x").getAsDouble();
        d.z = o.get("z").getAsDouble();
        d.depth = o.get("depth").getAsDouble();
        d.tipElevation = o.get("tipElevation").getAsDouble();
        d.opening = o.get("opening").getAsDouble();
        d.strikeLength = o.get("strikeLength").getAsDouble();
        d.volume = o.get("volume").getAsDouble();
        d.speed = o.get("speed").getAsDouble();
        d.noiseX = o.get("noiseX").getAsDouble();
        d.noiseZ = o.get("noiseZ").getAsDouble();
        d.travelX = o.get("travelX").getAsDouble();
        d.travelZ = o.get("travelZ").getAsDouble();
        d.removed = o.has("removed") && o.get("removed").getAsBoolean();
        if (o.has("fissure")) {
            JsonObject f = o.getAsJsonObject("fissure");
            d.fissure = new VentSite(f.get("id").getAsString(), Point3.fromJson(f.get("position")), VentKind.FISSURE,
                    f.get("halfWidth").getAsDouble(), f.get("angle").getAsDouble(), f.get("length").getAsDouble());
        }
        return d;
    }
}
