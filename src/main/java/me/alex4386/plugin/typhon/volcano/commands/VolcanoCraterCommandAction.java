package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonCommand;
import me.alex4386.plugin.typhon.TyphonCommandAction;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public enum VolcanoCraterCommandAction {
    START("start", "", "start this crater's eruption"),
    STOP("stop", "", "stop this crater's eruption"),
    HELP("help", "", "shows this help menu"),
    CONFIG("config", "<name> <?value>", "configure the crater"),
    INFO("info", "", "shows the information menu"),
    ERUPT("erupt", "<start / stop / now> <? bombCount>", "start or stop eruption scheduler / or erupt now"),
    LAVA_FLOW("lavaflow", "<start / stop / now> <? flowamount>", "start or stop lavaflow scheduler / or flow lava now"),
    SUMMIT("summit", "", "get navigation to summit"),
    QUICK_COOL("quickcool", "", "cool all lava from this crater"),
    TREMOR("tremor", "<? power>", "create volcano tremor"),
    STATUS("status", "<? status>", "get/set status of this crater"),
    CREATE_SUB("createSub", "<? minRange> <? maxRange>", "create subcrater from this crater"),
    DELETE("delete", "", "delete this crater");

    String cmdline;
    String usage;
    String explanation;

    VolcanoCraterCommandAction(String cmdline, String usage, String explanation) {
        this.cmdline = cmdline;
        this.usage = usage;
        this.explanation = explanation;
    }

    public static List<String> listAll(CommandSender sender) {
        List<String> all = new ArrayList<>();

        for (VolcanoCraterCommandAction action : VolcanoCraterCommandAction.values()) {
            if (action.hasPermission(sender)) {
                all.add(action.getCommand());
            }
        }

        return all;
    }

    public String getManual(String label, String name, String craterName) {
        String commandType = "subCrater";
        if (craterName == null) {
            commandType = "mainCrater";
        } else if (craterName.equals("") || craterName.equals("main")) {
            commandType = "mainCrater";
        } else {
            commandType += " "+craterName;
        }
        return ChatColor.LIGHT_PURPLE+"/"+label+" "+ChatColor.AQUA+name+" "+commandType+" "+ChatColor.YELLOW+this.cmdline+" "+ChatColor.GRAY+this.usage+ChatColor.RESET+" : "+this.explanation;
    }

    public static String getAllManual(CommandSender sender, String label, String name, String craterName) {
        String all = "";

        for (VolcanoCraterCommandAction action : VolcanoCraterCommandAction.values()) {
            if (action.hasPermission(sender)) {
                all += action.getManual(label, name, craterName)+"\n";
            }
        }

        return all;
    }

    public String getCommand() {
        return cmdline;
    }

    public boolean hasPermission(CommandSender sender) {
        return TyphonCommand.hasPermission(sender, "crater."+this.cmdline);
    }

    public static VolcanoCraterCommandAction getAction(String string) {
        for (VolcanoCraterCommandAction action : VolcanoCraterCommandAction.values()) {
            if (action.getCommand().equalsIgnoreCase(string)) {
                return action;
            }
        }
        return null;
    }
}
