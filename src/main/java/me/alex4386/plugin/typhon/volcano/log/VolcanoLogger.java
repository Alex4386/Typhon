package me.alex4386.plugin.typhon.volcano.log;

import me.alex4386.plugin.typhon.volcano.Volcano;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

public class VolcanoLogger {
    Volcano volcano = null;
    boolean isDebug = false;

    public VolcanoLogger() {}

    public VolcanoLogger(boolean isDebug) { this.isDebug = isDebug; }

    public VolcanoLogger(Volcano volcano) { this.volcano = volcano; this.isDebug = volcano.isDebug; }

    public VolcanoLogger(Volcano volcano, boolean isDebug) {
        this.volcano = volcano;
        this.isDebug = isDebug;
    }

    public void setDebug(boolean isDebug) {
        isDebug = isDebug;
        if (volcano != null) {
            volcano.isDebug = isDebug;
        }
    }

    public boolean getDebug() {
        if (volcano == null) {
            return this.isDebug;
        }
        return this.volcano.isDebug;
    }

    public String getHeader() {
        String string = "";
        string += ChatColor.GOLD;
        string += "[";
        string += volcano == null ? "SYSTEM" : volcano.name;
        string += "]";
        string += ChatColor.RESET;
        string += " ";

        return string;
    }

    // Use this when the logging is unnecessary but
    // developer or advanced user needs to see.
    public void debug(VolcanoLogClass logClass, String string) {
        String headers = logClass.getHeader() + this.getHeader();

        if (getDebug()) {
            Bukkit.getLogger().info(headers+string);
        }
    }

    // Use this when the logging to terminal is absolutely necessary.
    // and admin needs to know what is happening on volcano.
    public void log(VolcanoLogClass logClass, String string) {
        String headers = logClass.getHeader() + this.getHeader();

        Bukkit.getLogger().info(headers+string);
    }

    // Use this something is wrong with the plugin but plugin can continue.
    // admin needs to acknowledge this issue asap and fix it.
    public void warn(VolcanoLogClass logClass, String string) {
        String headers = logClass.getHeader() + this.getHeader();

        string.replace(ChatColor.RESET.toString(), ChatColor.YELLOW.toString());
        Bukkit.getLogger().severe(headers+ChatColor.YELLOW+string);
    }

    // Use this something is wrong with the plugin but plugin can NOT continue.
    // admin needs to fix this issue immediately
    public void error(VolcanoLogClass logClass, String string) {
        String headers = logClass.getHeader() + this.getHeader();

        string.replace(ChatColor.RESET.toString(), ChatColor.RED.toString());
        Bukkit.getLogger().severe(headers+ChatColor.RED+string);
    }
}

