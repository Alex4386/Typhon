package me.alex4386.plugin.typhon;

import me.alex4386.plugin.typhon.volcano.commands.VolcanoCommandUtils;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

enum TyphonDebugCommandAction {
    TEST("test", "", "Test this feature");

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
        return ChatColor.LIGHT_PURPLE+"/"+label+" "+ChatColor.YELLOW+this.cmdline+" "+ChatColor.GRAY+this.usage+ChatColor.RESET+" : "+this.explanation;
    }

    public static String getAllManual(CommandSender sender, String label) {
        String all = "";

        for (TyphonDebugCommandAction action : TyphonDebugCommandAction.values()) {
            all += action.getManual(label)+"\n";
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
        sender.sendMessage(""+
                ChatColor.RED+ChatColor.BOLD+
                "[Typhon Plugin: "+
                ChatColor.YELLOW+"DEBUG"+
                ChatColor.RED+ChatColor.BOLD+"]"+
                ChatColor.RESET+" "+
                msg);
    }

    public static boolean onCommand(CommandSender sender, String[] newArgs) {
        if (newArgs.length == 0) {
            sender.sendMessage(TyphonDebugCommandAction.getAllManual(sender, "/vol debug"));
            return true;
        }

        if (newArgs.length >= 1) {
            TyphonDebugCommandAction action = TyphonDebugCommandAction.getAction(newArgs[0]);
            switch(action) {
                case TEST:
                    sendMessage(sender, "Test!");
                    return true;
                default:
                    sendMessage(sender, "Unknown Command");
                    return true;
            }
        }

        sender.sendMessage("Debug Command Failed: "+newArgs[1]);
        return true;
    }
}
