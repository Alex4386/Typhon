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
        if (from.getWorld().getUID() != to.getWorld().getUID()) {
            return null;
        }

        float userYawN = from.getYaw() - 180;
        userYawN = (userYawN < 0) ? userYawN + 360 : userYawN;

        double distanceN = from.getBlockZ() - to.getBlockZ();
        double distanceE = to.getBlockX() - from.getBlockX();
        double distanceDirect = Math.sqrt(Math.pow(distanceN, 2) + Math.pow(distanceE, 2));

        double theta;
        theta = Math.toDegrees(Math.acos(distanceN / distanceDirect));

        TyphonPlugin.logger.debug(
                VolcanoLogClass.MATH,
                "Caclulated Navigation / target theta: " + theta + ", userYawN: " + userYawN);

        double destinationYaw = theta - userYawN;
        destinationYaw =
                destinationYaw > 180
                        ? -(360 - destinationYaw)
                        : destinationYaw < -180 ? (360 + destinationYaw) : destinationYaw;

        if (Double.isNaN(destinationYaw)) {
            destinationYaw = 0;
        }

        return new TyphonNavigation(destinationYaw, distanceDirect);
    }
}
