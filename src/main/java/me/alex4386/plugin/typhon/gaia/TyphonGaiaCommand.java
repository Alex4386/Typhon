package me.alex4386.plugin.typhon.gaia;

import me.alex4386.plugin.typhon.TyphonMessage;
import me.alex4386.plugin.typhon.TyphonPlugin;
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

            if (cmd.equalsIgnoreCase("worlds")) {
                if (newArgs.length == 1) {
                    if (!sender.hasPermission("typhon.gaia.worlds")) {
                        noPermission(sender);
                        return true;
                    }

                    sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Enabled Worlds:");
                    for (World world : TyphonGaia.enabledWorlds) {
                        sender.sendMessage("- "+world.getName());
                    }

                    sender.sendMessage("");
                    sender.sendMessage("Commands:");
                    sender.sendMessage("/typhon gaia worlds enable <world> : Enable the specified world");
                    sender.sendMessage("/typhon gaia worlds disable <world> : Disable the specified world");
                } else if (newArgs.length >= 2) {
                    if (newArgs.length >= 3) {
                        String action = newArgs[1].toLowerCase();
                        World world = Bukkit.getWorld(newArgs[2]);
                        if (world == null) {
                            errorMessage(sender, "The specified world does not exist.");
                            return true;
                        }

                        if (action.equalsIgnoreCase("enable")) {
                            if (!sender.hasPermission("typhon.gaia.worlds.enable")) {
                                noPermission(sender);
                                return true;
                            }

                            TyphonGaia.enableWorld(world);
                            sender.sendMessage(ChatColor.DARK_GREEN + "The specified world ("+world.getName()+") has been enabled.");
                            return true;
                        } else if (action.equalsIgnoreCase("disable")) {
                            if (!sender.hasPermission("typhon.gaia.worlds.disable")) {
                                noPermission(sender);
                                return true;
                            }

                            TyphonGaia.disableWorld(world);
                            sender.sendMessage(ChatColor.DARK_GREEN + "The specified world ("+world.getName()+") has been disabled.");

                            return true;
                        }
                    }

                    sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Typhon Gaia]");
                    sender.sendMessage("Usage: /typhon gaia worlds <enable|disable> <world>");
                    return true;
                }
            } else if (cmd.equalsIgnoreCase("help")) {
                sendHelp(sender);
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
    }

    public static void noPermission(CommandSender sender) {
        errorMessage(sender, "You do not have permission to use this command.");
    }

    public static void errorMessage(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Typhon Gaia]");
        sender.sendMessage(message);
    }
}
