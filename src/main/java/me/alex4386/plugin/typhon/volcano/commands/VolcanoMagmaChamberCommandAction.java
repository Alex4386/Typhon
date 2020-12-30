package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonCommand;
import me.alex4386.plugin.typhon.TyphonCommandAction;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public enum VolcanoMagmaChamberCommandAction {
    BUILD("build", "<useNMS>", "build this magmachamber"),
    FILL("fill", "<useNMS>", "fill this magmachamber"),
    COOL("cool", "<useNMS>", "cooldown this magmachamber"),
    HELP("help", "", "show help of this command"),
    HEIGHT("height", "<value>", "update height of the magmachamber - readonly after built"),
    RADIUS("radius", "<value>", "update radius of the magmachamber - readonly after built"),
    BASE("base", "<baseY>", "update baseY of the magmachamber - readonly after built"),
    DEBUG_RESET("debug:reset", "", "reset this magmachamber"),
    DELETE("delete", "", "delete this magmachamber"),
    INFO("info", "", "give info about magmachamber");

    String cmdline;
    String usage;
    String explanation;

    VolcanoMagmaChamberCommandAction(String cmdline, String usage, String explanation) {
        this.cmdline = cmdline;
        this.usage = usage;
        this.explanation = explanation;
    }

    public static List<String> listAll(CommandSender sender) {
        List<String> all = new ArrayList<>();

        for (VolcanoMagmaChamberCommandAction action : VolcanoMagmaChamberCommandAction.values()) {
            if (TyphonCommand.hasPermission(sender, "magmachamber."+action.getCommand())) {
                all.add(action.getCommand());
            }
        }

        return all;
    }

    public String getManual(String label, String name, String magmaChamberName) {
        return ChatColor.LIGHT_PURPLE+"/"+label+" "+ChatColor.AQUA+name+" magmaChamber "+magmaChamberName+" "+ChatColor.YELLOW+this.cmdline+" "+ChatColor.GRAY+this.usage+ChatColor.RESET+" : "+this.explanation;
    }

    public static String getAllManual(CommandSender sender, String label, String name, String magmaChamberName) {
        String all = "";

        for (VolcanoMagmaChamberCommandAction action : VolcanoMagmaChamberCommandAction.values()) {
            if (action.hasPermission(sender)) {
                all += action.getManual(label, name, magmaChamberName)+"\n";
            }
        }

        return all;
    }

    public String getCommand() {
        return cmdline;
    }

    public boolean hasPermission(CommandSender sender) {
        return TyphonCommand.hasPermission(sender, "magmachamber."+this.cmdline);
    }

    public static VolcanoMagmaChamberCommandAction getAction(String string) {
        for (VolcanoMagmaChamberCommandAction action : VolcanoMagmaChamberCommandAction.values()) {
            if (action.getCommand().equalsIgnoreCase(string)) {
                return action;
            }
        }
        return null;
    }
}
