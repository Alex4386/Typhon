package me.alex4386.plugin.typhon.volcano.log;

import org.bukkit.ChatColor;

public enum VolcanoLogClass {
    INIT("Init"),
    CORE("Core"),
    ASH("Ash"),
    AUTOSTART("Autostart"),
    BOMB("Bomb"),
    BOMB_LAUNCHER("Bomb Launcher"),
    BLUE_MAP("Blue Map"),
    COMMAND("Command"),
    COMPOSITION("Composition"),
    CONSTRUCTION("Construction"),
    EXPLOSION("Explosion"),
    LAVA_FLOW("Lava flow"),
    GEOTHERMAL("Geothermal"),
    MAGMA_INTRUSION("Magma Intrusion"),
    MATH("Math"),
    METAMORPHISM("Metamorphism"),
    PLAYER_EVENT("Player Event"),
    SUCCESSION("Succession"),
    TREMOR("Tremor"),
    UTILS("Utils"),
    VENT("Vent"),
    ;

    private final String string;

    private VolcanoLogClass(String string) {
        this.string = string;
    }

    public String toString() {
        return string;
    }

    public String getHeader() {
        return ChatColor.RED + "[" + this.string + "]" + ChatColor.RESET + " ";
    }
}
