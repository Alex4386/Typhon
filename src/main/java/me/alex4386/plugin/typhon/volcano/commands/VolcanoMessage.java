package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.volcano.Volcano;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

public class VolcanoMessage {

    public Volcano volcano;
    public CommandSender sender;

    public static String volcanoEmoji = "\uD83C\uDF0B";

    public VolcanoMessage(Volcano volcano) {
        this.volcano = volcano;
    }

    VolcanoMessage(CommandSender sender) {
        this.sender = sender;
    }

    public VolcanoMessage(Volcano volcano, CommandSender sender) {
        this.volcano = volcano;
        this.sender = sender;
    }

    public void ok(String msg) {
        VolcanoMessage.ok(this.sender, this.volcano, msg);
    }

    public void info(String msg) {
        VolcanoMessage.info(this.sender, this.volcano, msg);
    }

    public void warn(String msg) {
        VolcanoMessage.warn(this.sender, this.volcano, msg);
    }

    public void error(String msg) {
        VolcanoMessage.error(this.sender, this.volcano, msg);
    }

    public void ok(CommandSender sender, String msg) {
        VolcanoMessage.ok(sender, this.volcano, msg);
    }

    public void info(CommandSender sender, String msg) {
        VolcanoMessage.info(sender, this.volcano, msg);
    }

    public void warn(CommandSender sender, String msg) {
        VolcanoMessage.warn(sender, this.volcano, msg);
    }

    public void error(CommandSender sender, String msg) {
        VolcanoMessage.error(sender, this.volcano, msg);
    }

    public static String generateText(CommandSender sender, ChatColor color, Volcano volcano, String prefix, String consolePrefix, String msg) {
        String typhonPrefix = ChatColor.RED + volcanoEmoji + " " + volcano.name + " ";
        String realPrefix = prefix;

        if (sender instanceof ConsoleCommandSender) {
            realPrefix = consolePrefix;
            typhonPrefix = ChatColor.RED + "[" + volcano.name + "]";
        }

        return typhonPrefix + color + realPrefix + ": " + ChatColor.RESET + msg;
    }

    public static void ok(CommandSender sender, Volcano volcano, String msg) {
        sender.sendMessage(
                VolcanoMessage.generateText(sender, ChatColor.GREEN, volcano, "☑", "(v)", msg)
        );
    }

    public static void info(CommandSender sender, Volcano volcano, String msg) {
        sender.sendMessage(
                VolcanoMessage.generateText(sender, ChatColor.AQUA, volcano, "ℹ", "(i)", msg));
    }

    public static void warn(CommandSender sender, Volcano volcano, String msg) {
        sender.sendMessage(
                VolcanoMessage.generateText(sender, ChatColor.GOLD, volcano, "⚠", "(!)", msg)
        );
    }

    public static void error(CommandSender sender, Volcano volcano, String msg) {
        sender.sendMessage(
                VolcanoMessage.generateText(sender, ChatColor.RED, volcano, "☒", "(x)", msg)
        );
    }
}
