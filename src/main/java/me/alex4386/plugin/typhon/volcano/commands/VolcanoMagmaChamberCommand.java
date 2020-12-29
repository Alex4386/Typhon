package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonCommand;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMagmaChamber;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoConstructionType;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

public class VolcanoMagmaChamberCommand {
    VolcanoMagmaChamber magmaChamber;

    public VolcanoMagmaChamberCommand(VolcanoMagmaChamber magmaChamber) {
        this.magmaChamber = magmaChamber;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        String[] newArgs = VolcanoCommandUtils.parseSubmenuCommand("magmachamber", args);

        String operationName = newArgs[0];

        if (newArgs.length == 1) {
            return TyphonCommand.search(operationName, VolcanoMagmaChamberCommandAction.listAll(sender));
        }
        return null;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String[] newArgs = VolcanoCommandUtils.parseSubmenuCommand("magmachamber", args);

        String operationName = "";
        operationName = newArgs.length > 0 ? newArgs[0] : "";
        VolcanoMagmaChamberCommandAction action = VolcanoMagmaChamberCommandAction.getAction(operationName);

        VolcanoMessage msg = new VolcanoMessage(this.magmaChamber.volcano, sender);

        if (action != null) {
            if (!action.hasPermission(sender)) {
                msg.error("You don't have enough permission to run "+action.getCommand());
                return true;
            }
        } else {
            action = VolcanoMagmaChamberCommandAction.INFO;
        }

        switch(action) {
            case COOL:
                if (!magmaChamber.isFilled) {
                    msg.error("Magma chamber "+magmaChamber.name+" is not yet filled");
                    break;
                }
                magmaChamber.coolDown(sender);
                msg.info("Magma chamber "+magmaChamber.name+" is now cooling down...");
                break;
            case FILL:
                if (magmaChamber.isFilled) {
                    msg.error("Magma chamber "+magmaChamber.name+" seems to be already filled");
                    break;
                }
                magmaChamber.fill(sender);
                msg.info("Magma chamber "+magmaChamber.name+" is now filling up...");
                break;
            case BUILD:
                if (magmaChamber.isBuilt) {
                    msg.error("Magma chamber "+magmaChamber.name+" seems to be already built");
                    break;
                }
                magmaChamber.build(sender);

                msg.info("Magma chamber "+magmaChamber.name+" is now building up...");
                break;
            case BUILD_NMS:
                if (magmaChamber.isBuilt) {
                    msg.error("Magma chamber "+magmaChamber.name+" seems to be already built");
                    break;
                }
                magmaChamber.build(sender, true);
                msg.info("Magma chamber "+magmaChamber.name+" is now building up with NMS mode...");
                break;

            case BUILD_BUKKIT:
                if (magmaChamber.isBuilt) {
                    msg.error("Magma chamber "+magmaChamber.name+" seems to be already built");
                    break;
                }
                magmaChamber.build(sender, false);
                msg.info("Magma chamber "+magmaChamber.name+" is now building up with Bukkit mode...");
                break;
            case HELP:
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcano Magma Chamber Command Manual");
                sender.sendMessage(VolcanoMagmaChamberCommandAction.getAllManual(sender, label, magmaChamber.volcano.name, magmaChamber.name));
                break;
            case DEBUG_RESET:
                magmaChamber.isFilled = false;
                magmaChamber.isBuilt = false;
                magmaChamber.constructionType = VolcanoConstructionType.NONE;
                magmaChamber.constructionStage = 0;
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin: DEBUG] "+ChatColor.GOLD+"Volcano Magma Chamber Reset Complete!");
                break;
            case INFO:
            default:
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcano MagmaChamber Info");
            msg.info("Location: "+ TyphonUtils.blockLocationTostring(magmaChamber.baseBlock));
                msg.info("Height  : "+ magmaChamber.height);
                msg.info("Radius  : "+ magmaChamber.baseRadius);
                msg.info("isFilled: "+ magmaChamber.isFilled);
                msg.info("isBuilt : "+ magmaChamber.isBuilt);
                break;
        }

        return true;
    }


}
