package me.alex4386.plugin.typhon.gaia;

import me.alex4386.plugin.typhon.TyphonMessage;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Arrays;

public class TyphonGaiaCommand {
    public static boolean onCommand(
            CommandSender sender, Command command, String label, String[] args) {
        String[] newArgs = Arrays.copyOfRange(args, 1, args.length);

        if (newArgs.length == 0) {
            if (!sender.hasPermission("typhon.gaia")) {
                noPermission(sender);
                return true;
            }

            sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Typhon Gaia]");
            sender.sendMessage("Automatic Volcanic eruption system");
            sender.sendMessage("");
            sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Enabled Worlds:");
            for (World world : TyphonGaia.enabledWorlds) {
                sender.sendMessage("- "+world.getName());
            }

            sender.sendMessage("");
            sendHelp(sender);
            return true;
        } else {
            String cmd = newArgs[0].toLowerCase();

            if (cmd.equalsIgnoreCase("enable-world") || cmd.equalsIgnoreCase("disable-world")) {
                String worldName = newArgs[1];
                String currentCmd = cmd.replace("-world", "");

                if (!sender.hasPermission("typhon.gaia.worlds." + currentCmd)) {
                    noPermission(sender);
                    return true;
                }

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    errorMessage(sender, "The specified world does not exist.");
                    return true;
                }

                if (currentCmd.equalsIgnoreCase("enable")) {
                    TyphonGaia.enableWorld(world);
                } else {
                    TyphonGaia.disableWorld(world);
                }

                sender.sendMessage(ChatColor.DARK_GREEN + "The specified world (" + world.getName() + ") has been " + currentCmd + "d.");
                return true;
            } else if (cmd.equalsIgnoreCase("worlds")) {
                if (!sender.hasPermission("typhon.gaia.worlds")) {
                    noPermission(sender);
                    return true;
                }

                if (newArgs.length == 1) {
                    sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Enabled Worlds:");
                    for (World world : TyphonGaia.enabledWorlds) {
                        sender.sendMessage("- "+world.getName());
                    }

                    sender.sendMessage("");
                    sender.sendMessage("Commands:");
                    sender.sendMessage("/typhon gaia worlds <world> : Gaia world settings commands");
                } else if (newArgs.length >= 2) {
                    World world = Bukkit.getWorld(newArgs[1]);
                    if (world == null) {
                        errorMessage(sender, "The specified world does not exist.");
                        return true;
                    } else if (!TyphonGaia.enabledWorlds.contains(world)) {
                        errorMessage(sender, "The World "+world.getName()+" is not enabled.");
                        sender.sendMessage("Enable it with /typhon gaia enable-world "+world.getName());
                        return true;
                    }

                    if (newArgs.length == 2) {
                        sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Typhon Gaia]");
                        sender.sendMessage("World Chunk count  : " + TyphonUtils.getChunkCount(world));
                        sender.sendMessage("World Area (Loaded): " + TyphonUtils.getChunkCount(world) * 256);
                        sender.sendMessage("Adequate volcanoes : " + TyphonGaia.getAdequateVolcanoCount(world));
                        sender.sendMessage("Current Volcanoes  : " +  VolcanoManager.getVolcanoesOnWorld(world).size());
                        sender.sendMessage("Active Volcanoes   : " +  VolcanoManager.getActiveVolcanoesOnWorld(world).size());
                        sender.sendMessage("");
                        sender.sendMessage("Usage: /typhon gaia worlds <world> help");
                        return true;
                    } else if (newArgs.length >= 3) {
                        String action = newArgs[2].toLowerCase();

                        if (action.equalsIgnoreCase("help")) {
                            sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Typhon Gaia] "+ChatColor.GOLD+"Gaia World commands");
                            sender.sendMessage("/typhon gaia worlds <world> help   : Show this help");
                            sender.sendMessage("/typhon gaia worlds <world> volcano: List currently detected volcanoes by gaia.");
                            sender.sendMessage("/typhon gaia worlds <world> balance: Balance the world with adequate amount of volcanoes.");
                        } else if (action.equalsIgnoreCase("volcano")) {
                            sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Typhon Gaia] " + ChatColor.GOLD + "Active Volcanoes");
                            VolcanoManager.getActiveVolcanoesOnWorld(world).forEach(volcano -> {
                                sender.sendMessage(
                                    ChatColor.DARK_RED
                                            + " - "
                                            + volcano.manager.getVolcanoChatColor()
                                            + volcano.name);
                            });
                        } else if (action.equalsIgnoreCase("balance")) {
                            TyphonGaia.runVolcanoSpawn();
                            sender.sendMessage("Gaia just balanced the world with adequate amount of volcanoes.");
                        } else {
                            sender.sendMessage("Usage: /typhon gaia worlds <world> help");
                        }

                        return true;
                    }

                    sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Typhon Gaia]");
                    sender.sendMessage("Usage: /typhon gaia worlds <enable|disable> <world>");
                    return true;
                }
            } else if (cmd.equalsIgnoreCase("help")) {
                sendHelp(sender);
            } else if (cmd.equalsIgnoreCase("bubble")) {
                if (newArgs.length == 1) {
                    sender.sendMessage(ChatColor.DARK_RED + "Gaia Volcano Bubble Radius: "+TyphonGaia.bubbleRadius);
                } else if (newArgs.length == 2) {
                    String valueRaw = newArgs[1];
                    int value = Integer.parseInt(valueRaw);

                    if (value <= 0) {
                        errorMessage(sender, "Value must be greater than 0.");
                        return true;
                    }

                    TyphonGaia.bubbleRadius = value;
                    TyphonGaia.saveConfig();

                    sender.sendMessage(ChatColor.DARK_RED + "Bubble radius set to " + value);
                } else {
                    errorMessage(sender, "Usage: /typhon gaia bubble <?value>");
                }
                return true;
            } else {
                errorMessage(sender, "Unknown command.");
                sender.sendMessage("Usage: /typhon gaia help");
            }

            return true;
        }
    }

    public static void sendHelp(CommandSender sender) {
        sender.sendMessage("Commands:");
        sender.sendMessage("/typhon gaia worlds: Use Gaia world management commands.");
        sender.sendMessage("/typhon gaia spawn : Use Gaia volcano spawn commands.");
        sender.sendMessage("/typhon gaia bubble <?value> : Get/Set Gaia volcano spawn \"bubble\".");
        sender.sendMessage("/typhon gaia enable-world <? world>: Enable world for Gaia.");
        sender.sendMessage("/typhon gaia disable-world <? world>: Disable world for Gaia.");
    }

    public static void noPermission(CommandSender sender) {
        errorMessage(sender, "You do not have permission to use this command.");
    }

    public static void errorMessage(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Typhon Gaia]");
        sender.sendMessage(message);
    }
}
