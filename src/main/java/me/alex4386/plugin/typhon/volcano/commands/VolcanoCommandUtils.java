package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonNavigation;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VolcanoCommandUtils {
    public static void findSummitAndSendToSender(CommandSender sender, VolcanoVent vent) {
        Block summitBlock = vent.getSummitBlock();

        if (sender instanceof Player) {
            Player player = ((Player) sender).getPlayer();
            String directions;

            if (player.getWorld() != summitBlock.getWorld()) {
                TyphonNavigation navigation = TyphonNavigation.getNavigation(player.getLocation(), summitBlock.getLocation());
                directions = navigation.getNavigation();
            } else {
                directions = "Different world!";
            }

            int toClimb = summitBlock.getY() - player.getLocation().getBlockY();

            sender.sendMessage("Directions: " + directions);
            sender.sendMessage("climb: " + toClimb + " blocks");
        }

        sender.sendMessage(
                "Located @ "
                        + TyphonUtils.blockLocationTostring(summitBlock)
                        + " on vent "
                        + vent.getName());
    }

    public static String getSubmenuName(String submenuString, String[] args) {
        // vol name mainVent command
        // -1 0 1 2

        // vol name subVent name command
        // -1 0 1 2 3

        List<String> parsedArgs = new ArrayList<>(Arrays.asList(args));
        parsedArgs.remove(0);

        if (parsedArgs.get(0).equalsIgnoreCase(submenuString)) {
            return parsedArgs.get(1);
        }

        return null;
    }

    public static String[] parseSubmenuCommand(String submenuString, String[] args) {
        // vol name mainVent command
        // -1 0 1 2

        // vol name subVent name command
        // -1 0 1 2 3

        List<String> parsedArgs = new ArrayList<>(Arrays.asList(args));
        parsedArgs.remove(0);

        if (parsedArgs.get(0).equalsIgnoreCase(submenuString)) {
            parsedArgs.remove(0);
        }
        parsedArgs.remove(0);

        return parsedArgs.toArray(new String[parsedArgs.size()]);
    }
}
