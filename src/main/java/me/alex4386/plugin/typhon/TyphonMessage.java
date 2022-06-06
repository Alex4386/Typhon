package me.alex4386.plugin.typhon;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public class TyphonMessage {

    public CommandSender sender;

    TyphonMessage(CommandSender sender) {
        this.sender = sender;
    }

    public void info(String msg) {
        TyphonMessage.info(sender, msg);
    }

    public void warn(String msg) {
        TyphonMessage.warn(sender, msg);
    }

    public void error(String msg) {
        TyphonMessage.error(sender, msg);
    }

    public static void info(CommandSender sender, String msg) {
        sender.sendMessage(ChatColor.BLUE + "[Typhon: INFO] " + ChatColor.RESET + msg);
    }

    public static void warn(CommandSender sender, String msg) {
        sender.sendMessage(ChatColor.GOLD + "[Typhon: WARN] " + ChatColor.RESET + msg);
    }

    public static void error(CommandSender sender, String msg) {
        sender.sendMessage(ChatColor.RED + "[Typhon: ERROR] " + ChatColor.RESET + msg);
    }
}
