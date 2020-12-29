package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.volcano.Volcano;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public class VolcanoMessage {

    public Volcano volcano;
    public CommandSender sender;

    public VolcanoMessage(Volcano volcano) { this.volcano = volcano; }
    VolcanoMessage(CommandSender sender) {
        this.sender = sender;
    }

    VolcanoMessage(Volcano volcano, CommandSender sender) {
        this.volcano = volcano;
        this.sender = sender;
    }

    public void info(String msg) {
        VolcanoMessage.info(this.sender, this.volcano, msg);
    }

    public void warn(String msg) { VolcanoMessage.warn(this.sender, this.volcano, msg); }

    public void error(String msg) {
        VolcanoMessage.error(this.sender, this.volcano, msg);
    }

    public void info(CommandSender sender, String msg) {
        VolcanoMessage.info(sender, this.volcano, msg);
    }

    public void warn(CommandSender sender, String msg) { VolcanoMessage.warn(sender, this.volcano, msg); }

    public void error(CommandSender sender, String msg) {
        VolcanoMessage.error(sender, this.volcano, msg);
    }

    public static void info(CommandSender sender, Volcano volcano, String msg) {
        sender.sendMessage(ChatColor.AQUA+"[Volcano - "+volcano.name+": INFO] "+ChatColor.RESET+msg);
    }

    public static void warn(CommandSender sender, Volcano volcano, String msg) {
        sender.sendMessage(ChatColor.GOLD+"[Volcano - "+volcano.name+": WARN] "+ChatColor.RESET+msg);
    }

    public static void error(CommandSender sender, Volcano volcano, String msg) {
        sender.sendMessage(ChatColor.RED+"[Volcano - "+volcano.name+": ERROR] "+ChatColor.RESET+msg);
    }
}
