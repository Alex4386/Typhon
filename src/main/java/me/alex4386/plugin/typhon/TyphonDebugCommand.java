package me.alex4386.plugin.typhon;

import me.alex4386.plugin.typhon.volcano.Volcano;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.material.MaterialData;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

enum TyphonDebugCommandAction {
    TEST("test", "", "Test this feature"),
    BLUEMAP_RERENDER("bluemap_rerender", "", "Rerender the bluemap");

    String cmdline;
    String usage;
    String explanation;

    TyphonDebugCommandAction(String cmdline, String usage, String explanation) {
        this.cmdline = cmdline;
        this.usage = usage;
        this.explanation = explanation;
    }

    public String getCommand() {
        return cmdline;
    }

    public static List<String> getCmdlineValues() {
        List<String> cmdlines = new ArrayList<>();
        for (TyphonDebugCommandAction action : TyphonDebugCommandAction.values()) {
            cmdlines.add(action.getCommand());
        }
        return cmdlines;
    }

    public static TyphonDebugCommandAction getAction(String string) {
        for (TyphonDebugCommandAction action : TyphonDebugCommandAction.values()) {
            if (action.getCommand().equalsIgnoreCase(string)) {
                return action;
            }
        }
        return null;
    }

    public String getManual(String label) {
        return ChatColor.LIGHT_PURPLE
                + "/"
                + label
                + " "
                + ChatColor.YELLOW
                + this.cmdline
                + " "
                + ChatColor.GRAY
                + this.usage
                + ChatColor.RESET
                + " : "
                + this.explanation;
    }

    public static String getAllManual(CommandSender sender, String label) {
        String all = "";

        for (TyphonDebugCommandAction action : TyphonDebugCommandAction.values()) {
            all += action.getManual(label) + "\n";
        }

        return all;
    }
}

public class TyphonDebugCommand {
    public static boolean canRunDebug(CommandSender sender) {
        return sender.hasPermission("typhon.debug");
    }

    public static String[] convertToDebugNewArgs(String[] args) {
        List<String> newArgsList = new ArrayList<>(Arrays.asList(args));
        newArgsList.remove(0);

        return newArgsList.toArray(new String[newArgsList.size()]);
    }

    public static List<String> onTabComplete(CommandSender sender, String[] newArgs) {
        if (newArgs.length == 1) {
            String searchQuery = newArgs[0];
            return TyphonCommand.search(searchQuery, TyphonDebugCommandAction.getCmdlineValues());
        }

        return null;
    }

    public static void sendMessage(CommandSender sender, String msg) {
        sender.sendMessage(
                ""
                        + ChatColor.RED
                        + ChatColor.BOLD
                        + "[Typhon Plugin: "
                        + ChatColor.YELLOW
                        + "DEBUG"
                        + ChatColor.RED
                        + ChatColor.BOLD
                        + "]"
                        + ChatColor.RESET
                        + " "
                        + msg);
    }

    public static boolean onCommand(CommandSender sender, String[] newArgs) {
        if (newArgs.length == 0) {
            sender.sendMessage(TyphonDebugCommandAction.getAllManual(sender, "/vol debug"));
            return true;
        }

        if (newArgs.length >= 1) {
            TyphonDebugCommandAction action = TyphonDebugCommandAction.getAction(newArgs[0]);
            Player player = (sender instanceof Player) ? (Player) sender : null;

            switch (action) {
                case TEST: {
                    onTest(sender, newArgs);
                    return true;
                }
                case BLUEMAP_RERENDER: {
                    if (TyphonBlueMapUtils.getBlueMapAvailable()) {
                        if (newArgs.length >= 2) {
                            String volcanoName = newArgs[1];
                            if (TyphonPlugin.listVolcanoes.containsKey(volcanoName)) {
                                Volcano volcano = TyphonPlugin.listVolcanoes.get(volcanoName);
                                TyphonBlueMapUtils.reRenderVolcano(volcano);
                                sendMessage(sender, "Rerender has queued for the volcano "+volcanoName);
                            } else {
                                sendMessage(sender, "Volcano not found");
                            }
                        } else {
                            sendMessage(sender, "Please specify the volcano name");
                        }
                        return true;
                    }
                    sendMessage(sender, "BlueMap is not available");
                    return true;
                }
                default:
                    sendMessage(sender, "Unknown Command");
                    return true;
            }
        }

        sender.sendMessage("Debug Command Failed: " + newArgs[0]);
        return true;
    }


    public static void onTest(CommandSender sender, String[] newArgs) {
        Player player = (sender instanceof Player) ? (Player) sender : null;

        sendMessage(sender, "Test!");
        if (newArgs.length >= 4) {
            float powerX = Float.parseFloat(newArgs[1]);
            float powerY = Float.parseFloat(newArgs[2]);
            float powerZ = Float.parseFloat(newArgs[3]);

            if (player != null) {
                Location startLocation = player.getLocation();
                Location wantedDestination =
                        player.getLocation().add(powerX, powerY, powerZ);
                FallingBlock block =
                        player.getWorld()
                                .spawnFallingBlock(
                                        startLocation,
                                        new MaterialData(Material.GRAVEL));
                block.setVelocity(
                        TyphonUtils.calculateVelocity(
                                new Vector(0, 0, 0),
                                new Vector(powerX, powerY, powerZ),
                                5));

                block.setGravity(true);
                block.setInvulnerable(true);
                block.setDropItem(false);

                AtomicInteger i = new AtomicInteger();
                final Location[] prevLocation = {player.getLocation()};
                AtomicReference<Location> highestLocation =
                        new AtomicReference<>(player.getLocation());

                boolean startup = false;
                if (newArgs.length == 5) {
                    startup = Boolean.parseBoolean(newArgs[4]);
                }

                boolean finalStartup = startup;
                i.set(
                        TyphonScheduler.registerGlobalTask(
                                () -> {
                                    Location location = block.getLocation();
                                    double offsetX =
                                            location.getX()
                                                    - prevLocation[0].getX();
                                    double offsetY =
                                            location.getY()
                                                    - prevLocation[0].getY();
                                    double offsetZ =
                                            location.getZ()
                                                    - prevLocation[0].getZ();

                                    if (block.isOnGround()
                                            || block.isDead()
                                            || (offsetX == 0
                                            && offsetY == 0
                                            && offsetZ == 0)
                                            || (location.getBlockY()
                                            == startLocation
                                            .getBlockY()
                                            && finalStartup
                                            && location.distance(
                                            startLocation)
                                            > 10)) {
                                        Location destination =
                                                block.getLocation();
                                        sendMessage(sender, "FINAL");
                                        sendMessage(
                                                sender,
                                                TyphonUtils
                                                        .blockLocationTostring(
                                                                destination
                                                                        .getBlock()));
                                        sendMessage(
                                                sender, "Distance Travelled");
                                        sendMessage(
                                                sender,
                                                "x: "
                                                        + (destination.getX()
                                                        - startLocation
                                                        .getX()));
                                        sendMessage(
                                                sender,
                                                "y: "
                                                        + (destination.getY()
                                                        - startLocation
                                                        .getY()));
                                        sendMessage(
                                                sender,
                                                "z: "
                                                        + (destination.getZ()
                                                        - startLocation
                                                        .getZ()));
                                        sendMessage(
                                                sender,
                                                "Highest - height: "
                                                        + (highestLocation
                                                        .get()
                                                        .getY()
                                                        - startLocation
                                                        .getY()));
                                        sendMessage(
                                                sender,
                                                TyphonUtils
                                                        .blockLocationTostring(
                                                                highestLocation
                                                                        .get()
                                                                        .getBlock()));
                                        Bukkit.getScheduler()
                                                .cancelTask(i.get());
                                        block.getLocation()
                                                .getBlock()
                                                .setType(Material.AIR);
                                    }

                                    if (highestLocation.get().getY()
                                            < location.getY()) {
                                        highestLocation.set(location);
                                    }

                                    String data =
                                            TyphonUtils.blockLocationTostring(
                                                    location.getBlock())
                                                    + (prevLocation[0] != null
                                                    ? " - "
                                                    + "x:"
                                                    + String
                                                    .format(
                                                            "%.2f",
                                                            offsetX)
                                                    + ", "
                                                    + "y:"
                                                    + String
                                                    .format(
                                                            "%.2f",
                                                            offsetY)
                                                    + ", "
                                                    + "z:"
                                                    + String
                                                    .format(
                                                            "%.2f",
                                                            offsetZ)
                                                    : "");

                                    sender.sendMessage(data);

                                    prevLocation[0] = location;
                                },
                                0L));
            }
        }
    }
}


