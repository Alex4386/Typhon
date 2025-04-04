package me.alex4386.plugin.typhon;

import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.Location;

public class TyphonNavigation {
    double yawDegree;
    double distance;

    TyphonNavigation(double yawDegree, double distance) {
        this.yawDegree = yawDegree;
        this.distance = distance;
    }

    public String getNavigation() {
        double destinationYaw = yawDegree;
        double directDistance = distance;

        String destinationString =
                Math.abs(Math.floor(destinationYaw))
                        + " degrees "
                        + ((Math.abs(destinationYaw) < 1)
                        ? "Forward"
                        : (destinationYaw < 0) ? "Left" : "Right")
                        + ((Math.abs(destinationYaw) > 135) ? " Backward" : "");

        if (Double.isNaN(destinationYaw) || directDistance < 1) {
            return "Arrived!";
        }

        destinationString += " / " + String.format("%.2f", directDistance) + " blocks";

        return destinationString;
    }

    public static TyphonNavigation getNavigation(Location from, Location to) {
        // World detection
        if (!from.getWorld().getUID().equals(to.getWorld().getUID())) {
            return null;
        }

        // Vector based distance calculation
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Target Yaw
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        targetYaw = (targetYaw + 360) % 360;

        // Player Yaw
        float playerYawRaw = from.getYaw();
        double playerYaw = (playerYawRaw + 360) % 360;

        // Calculate diff
        double deltaYaw = ((targetYaw - playerYaw + 540) % 360) - 180;

        // Debug logging
        TyphonPlugin.logger.debug(
                VolcanoLogClass.MATH,
                "Calculated Navigation / targetYaw: " + targetYaw +
                        ", playerYaw: " + playerYaw +
                        ", deltaYaw: " + deltaYaw
        );

        return new TyphonNavigation(deltaYaw, distance);
    }
}
