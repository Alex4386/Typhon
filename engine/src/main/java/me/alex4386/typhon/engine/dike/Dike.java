package me.alex4386.typhon.engine.dike;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.deformation.DikeGeometry;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;

/**
 * One dike: a magma-filled crack rising from the chamber. Horizontal position is tracked in world
 * blocks, vertical progress as real depth below the surface (the model world compresses depth, so
 * the tip's world y is interpolated between the chamber and the surface).
 */
public final class Dike {
    final int id;
    final double startTime;
    final double startX;
    final double startZ;
    /** Surface y above the nucleation point; maps real depth to world y. */
    final int surfaceStartY;
    final int chamberY;
    /** Real depth of the chamber (m): the dike's lower edge. */
    final double chamberDepth;

    DikeStatus status = DikeStatus.PROPAGATING;
    double x;
    double z;
    /** Real depth of the upper tip (m). */
    double depth;
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
    int tipY;
    VentSite fissure;
    /** Deleted by the user: its fissure is no longer a vent; the intrusion stays in the rock. */
    boolean removed;

    Dike(int id, double startTime, double x, double z, int surfaceStartY, int chamberY, double chamberDepth) {
        this.id = id;
        this.startTime = startTime;
        this.startX = x;
        this.startZ = z;
        this.x = x;
        this.z = z;
        this.surfaceStartY = surfaceStartY;
        this.chamberY = chamberY;
        this.chamberDepth = chamberDepth;
        this.depth = chamberDepth;
        this.tipY = chamberY;
    }

    public int id() { return id; }
    /** Simulation time (s) at which the dike nucleated. */
    public double startTime() { return startTime; }
    public DikeStatus status() { return status; }
    public boolean propagating() { return status == DikeStatus.PROPAGATING; }
    /** Real depth of the upper tip below the surface (m). */
    public double depthM() { return depth; }
    /** Height of the dike from the chamber to its tip (m). */
    public double heightM() { return chamberDepth - depth; }
    public double openingM() { return opening; }
    public double strikeLengthM() { return strikeLength; }
    /** Magma volume intruded so far (m³). */
    public double volumeM3() { return volume; }
    /** Tip speed during the last step (m/s, physical). */
    public double speedMPerS() { return speed; }
    public double x() { return x; }
    public double z() { return z; }
    /** Where the dike left the chamber (m) on an {@code l}-metre grid. */
    public Point3 origin(double l) { return new Point3(startX * l, (chamberY + 0.5) * l, startZ * l); }
    /** The upper tip (m) on an {@code l}-metre grid; horizontally continuous. */
    public Point3 tip(double l) { return new Point3(x * l, (tipY + 0.5) * l, z * l); }
    /** The fissure this dike opened, or {@code null}. */
    public VentSite fissure() { return fissure; }
    /** True once the user deleted the dike: it is kept only as an intrusion (deformation, rock). */
    public boolean removed() { return removed; }

    /** Horizontal distance travelled from the nucleation point (blocks). */
    public double horizontalOffsetBlocks() {
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
        o.addProperty("surfaceStartY", surfaceStartY);
        o.addProperty("chamberY", chamberY);
        o.addProperty("chamberDepth", chamberDepth);
        o.addProperty("status", status.name());
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("depth", depth);
        o.addProperty("opening", opening);
        o.addProperty("strikeLength", strikeLength);
        o.addProperty("volume", volume);
        o.addProperty("speed", speed);
        o.addProperty("noiseX", noiseX);
        o.addProperty("noiseZ", noiseZ);
        o.addProperty("travelX", travelX);
        o.addProperty("travelZ", travelZ);
        o.addProperty("tipY", tipY);
        if (removed) o.addProperty("removed", true);
        if (fissure != null) {
            JsonObject f = new JsonObject();
            f.addProperty("id", fissure.id());
            f.addProperty("xM", fissure.position().x());
            f.addProperty("yM", fissure.position().y());
            f.addProperty("zM", fissure.position().z());
            f.addProperty("angle", fissure.fissureAngleRad());
            f.addProperty("length", fissure.fissureLength());
            o.add("fissure", f);
        }
        return o;
    }

    /** A saved dike on an {@code l}-metre grid (older saves give the fissure as a ground block). */
    static Dike load(JsonObject o, double l) {
        Dike d = new Dike(
                o.get("id").getAsInt(),
                o.get("startTime").getAsDouble(),
                o.get("startX").getAsDouble(),
                o.get("startZ").getAsDouble(),
                o.get("surfaceStartY").getAsInt(),
                o.get("chamberY").getAsInt(),
                o.get("chamberDepth").getAsDouble());
        d.status = DikeStatus.valueOf(o.get("status").getAsString());
        d.x = o.get("x").getAsDouble();
        d.z = o.get("z").getAsDouble();
        d.depth = o.get("depth").getAsDouble();
        d.opening = o.get("opening").getAsDouble();
        d.strikeLength = o.get("strikeLength").getAsDouble();
        d.volume = o.get("volume").getAsDouble();
        d.speed = o.get("speed").getAsDouble();
        d.noiseX = o.get("noiseX").getAsDouble();
        d.noiseZ = o.get("noiseZ").getAsDouble();
        d.travelX = o.get("travelX").getAsDouble();
        d.travelZ = o.get("travelZ").getAsDouble();
        d.tipY = o.get("tipY").getAsInt();
        d.removed = o.has("removed") && o.get("removed").getAsBoolean();
        if (o.has("fissure")) {
            JsonObject f = o.getAsJsonObject("fissure");
            d.fissure = new VentSite(
                    f.get("id").getAsString(),
                    f.has("xM") ? new Point3(f.get("xM").getAsDouble(), f.get("yM").getAsDouble(), f.get("zM").getAsDouble())
                            : Point3.surfaceOf(new BlockPos(f.get("x").getAsInt(), f.get("y").getAsInt(), f.get("z").getAsInt()), l),
                    VentKind.FISSURE,
                    1,
                    f.get("angle").getAsDouble(),
                    f.get("length").getAsInt());
        }
        return d;
    }
}
