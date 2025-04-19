package me.alex4386.plugin.typhon;

import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;

public class TyphonNavigation {
    double yawDegree;
    double distance;

    Location target;
    Location source;

    TyphonNavigation(Location target, Location source) {
        this.target = target;
        this.source = source;

        // Vector based distance calculation
        double dx = target.getX() - source.getX();
        double dz = target.getZ() - source.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Target Yaw
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        targetYaw = (targetYaw + 360) % 360;

        // Player Yaw
        float playerYawRaw = source.getYaw();
        double playerYaw = (playerYawRaw + 360) % 360;

        // Calculate diff
        double deltaYaw = ((targetYaw - playerYaw + 540) % 360) - 180;

        this.yawDegree = deltaYaw;
        this.distance = distance;
    }

    public String getNavigation() {
        double destinationYaw = yawDegree;
        double directDistance = distance;

        String destinationString = "";
        destinationString = Math.abs(Math.floor(destinationYaw))
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

    private String getSimpleDirectionLabel(double yaw) {
        double abs = Math.abs(yaw);
        if (abs < 15) return "Continue straight";
        if (abs < 45) return yaw < 0 ? "Slight left" : "Slight right";
        if (abs < 90) return yaw < 0 ? "Turn left" : "Turn right";
        if (abs < 135) return yaw < 0 ? "Sharp left" : "Sharp right";
        return yaw < 0 ? "U-turn left" : "U-turn right";
    }

    public double getClimb() {
        return target.getY() - source.getY();
    }

    public void sendToSender(CommandSender sender) {
        if (Double.isNaN(yawDegree) || distance < 1) {
            sender.sendMessage(ChatColor.GREEN + "✓ You’ve arrived at your destination!");
            return;
        }

        double climb = this.getClimb();

        String arrow = getArrow();
        String direction = getSimpleDirectionLabel(yawDegree);
        String distanceStr = String.format("%.0f", distance);
        String climbStr = (climb > 0 ? "+" : "") + String.format("%.0f", climb);

        // First line: direction and distance
        sender.sendMessage(ChatColor.YELLOW + arrow + " " + direction + " "
                + ChatColor.GRAY + "(" + distanceStr + " blocks)");

        // Second line: climb
        ChatColor climbColor = climb < 0 ? ChatColor.AQUA : (climb > 0 ? ChatColor.RED : ChatColor.GRAY);
        String climbArrow = climb > 0 ? "▲" : (climb < 0 ? "▼" : "-");

        sender.sendMessage(ChatColor.GOLD + "Climb: " + climbColor + climbStr + " blocks " + climbArrow);
    }

    public String getArrow() {
        if (Double.isNaN(yawDegree) || distance < 1) {
            return "✓"; // Arrived
        }

        double yaw = yawDegree;

        if (yaw <= -135) {
            return "↙"; // sharp left back
        } else if (yaw <= -45) {
            return "←"; // left
        } else if (yaw <= -15) {
            return "↖"; // slight left
        } else if (yaw < 15) {
            return "↑"; // forward
        } else if (yaw < 45) {
            return "↗"; // slight right
        } else if (yaw < 135) {
            return "→"; // right
        } else {
            return "↘"; // sharp right back
        }
    }

    public static TyphonNavigation getNavigation(Location from, Location to) {
        // World detection
        if (!from.getWorld().getUID().equals(to.getWorld().getUID())) {
            return null;
        }

        return new TyphonNavigation(to, from);
    }
}
