package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonCommand;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public enum VolcanoVentCommandAction {
    START("start", "", "start this vent's eruption"),
    STOP("stop", "", "stop this vent's eruption"),
    HELP("help", "", "shows this help menu"),
    CONFIG("config", "<name> <?value>", "configure the vent"),
    INFO("info", "", "shows the information menu"),
    SUMMIT("summit", "", "get navigation to summit"),
    QUICK_COOL("quickcool", "", "cool all lava from this vent"),
    LAVA_DOME("lavadome", "<start/stop/explode>", "start/stop building or explode this lavadome"),
    TREMOR("tremor", "<? power>", "create volcano tremor"),
    CALDERA("caldera", "<? radius/start> <? deep> <? oceanY>", "Create caldera via Plinian eruption"),
    STATUS("status", "<? status>", "get/set status of this vent"),
    TELEPORT("teleport", "", "teleport to this vent"),
    DELETE("delete", "", "delete this vent"),
    STYLE(
            "style",
            "<? hawaiian/strombolian/fissure/crater/etc.>",
            "get/set eruption style and type of this vent");

    String cmdline;
    String usage;
    String explanation;

    VolcanoVentCommandAction(String cmdline, String usage, String explanation) {
        this.cmdline = cmdline;
        this.usage = usage;
        this.explanation = explanation;
    }

    public static List<String> listAll(CommandSender sender) {
        List<String> all = new ArrayList<>();

        for (VolcanoVentCommandAction action : VolcanoVentCommandAction.values()) {
            if (action.hasPermission(sender)) {
                all.add(action.getCommand());
            }
        }

        return all;
    }

    public String getManual(String label, String name, String ventName) {
        String commandType = "subVent";
        if (ventName == null) {
            commandType = "mainVent";
        } else if (ventName.equals("") || ventName.equals("main")) {
            commandType = "mainVent";
        } else {
            commandType += " " + ventName;
        }
        return ChatColor.LIGHT_PURPLE
                + "/"
                + label
                + " "
                + ChatColor.AQUA
                + name
                + " "
                + commandType
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

    public static String getAllManual(
            CommandSender sender, String label, String name, String ventName) {
        String all = "";

        for (VolcanoVentCommandAction action : VolcanoVentCommandAction.values()) {
            if (action.hasPermission(sender)) {
                all += action.getManual(label, name, ventName) + "\n";
            }
        }

        return all;
    }

    public String getCommand() {
        return cmdline;
    }

    public boolean hasPermission(CommandSender sender) {
        return TyphonCommand.hasPermission(sender, "vent." + this.cmdline);
    }

    public static VolcanoVentCommandAction getAction(String string) {
        for (VolcanoVentCommandAction action : VolcanoVentCommandAction.values()) {
            if (action.getCommand().equalsIgnoreCase(string)) {
                return action;
            }
        }
        return null;
    }
}
