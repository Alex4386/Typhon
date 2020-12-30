package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonCommand;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMagmaChamber;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoConstructionType;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
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
        } else if (newArgs.length == 2) {
            VolcanoMagmaChamberCommandAction action = VolcanoMagmaChamberCommandAction.getAction(operationName);

            if (action != null) {
                if (action == VolcanoMagmaChamberCommandAction.COOL || action == VolcanoMagmaChamberCommandAction.FILL || action == VolcanoMagmaChamberCommandAction.BUILD) {
                    String[] res = { "<? useNMS>" };
                    return Arrays.asList(res);
                } else if (
                        action == VolcanoMagmaChamberCommandAction.HEIGHT ||
                        action == VolcanoMagmaChamberCommandAction.RADIUS ||
                        action == VolcanoMagmaChamberCommandAction.BASE
                ) {
                    String[] res = { "<value>" };
                    return Arrays.asList(res);
                }
            }
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

        boolean useNMS = false;
        if (newArgs.length == 2) useNMS = Boolean.parseBoolean(newArgs[1]);

        switch(action) {
            case COOL:
                if (!magmaChamber.isFilled) {
                    msg.error("Magma chamber "+magmaChamber.name+" is not yet filled");
                    break;
                }
                magmaChamber.coolDown(sender, useNMS);
                msg.info("Magma chamber "+magmaChamber.name+" is now cooling down "+(useNMS ? "using NMS Mode" : "using Bukkit Mode")+"...");
                break;
            case FILL:
                if (magmaChamber.isFilled) {
                    msg.error("Magma chamber "+magmaChamber.name+" seems to be already filled");
                    break;
                }
                magmaChamber.fill(sender, useNMS);
                msg.info("Magma chamber "+magmaChamber.name+" is now filling up "+(useNMS ? "using NMS Mode" : "using Bukkit Mode")+"...");
                break;
            case BUILD:
                if (magmaChamber.isBuilt) {
                    msg.error("Magma chamber "+magmaChamber.name+" seems to be already built");
                    break;
                }
                magmaChamber.build(sender, useNMS);

                msg.info("Magma chamber "+magmaChamber.name+" is now building up "+(useNMS ? "using NMS Mode" : "using Bukkit Mode")+"...");
                break;
            case HELP:
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcano Magma Chamber Command Manual");
                sender.sendMessage(VolcanoMagmaChamberCommandAction.getAllManual(sender, label, magmaChamber.volcano.name, magmaChamber.name));
                break;
            case DEBUG_RESET:
                magmaChamber.isFilled = false;
                magmaChamber.isBuilt = false;
                magmaChamber.constructionType = VolcanoConstructionType.NONE;
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin: DEBUG] "+ChatColor.GOLD+"Volcano Magma Chamber Reset Complete!");
                break;
            case DELETE:
                boolean success = magmaChamber.delete();
                if (magmaChamber.name == null) {
                    msg.error("You can NOT delete main crater.");
                }
                if (success) {
                    msg.info("Magma chamber has been successfully deleted.");
                } else {
                    msg.error("An error has occurred.");

                }
                break;
            case BASE:
                int baseY = magmaChamber.baseBlock.getY();
                if (newArgs.length == 2 && !magmaChamber.isBuilt) {
                    baseY = Integer.parseInt(newArgs[1]);
                    magmaChamber.baseBlock =
                            magmaChamber.baseBlock.getWorld().getBlockAt(
                                    magmaChamber.baseBlock.getX(),
                                    baseY,
                                    magmaChamber.baseBlock.getZ()
                            );
                } else if (newArgs.length == 2 && magmaChamber.isBuilt) {
                    msg.error("Read Only! This magmachamber was already built!");
                }

                msg.info("Magmachamber "+magmaChamber.name+"'s baseY = "+baseY);
                break;

            case HEIGHT:
                if (newArgs.length == 2) {
                    if (!magmaChamber.isBuilt) {
                        magmaChamber.height = Integer.parseInt(newArgs[1]);
                    } else {
                        msg.error("Read Only! This magmachamber was already built!");
                    }
                }
                msg.info("Magmachamber "+magmaChamber.name+"'s height = "+magmaChamber.height);
                break;

            case RADIUS:
                if (newArgs.length == 2) {
                    if (!magmaChamber.isBuilt) {
                        magmaChamber.baseRadius = Integer.parseInt(newArgs[1]);
                    } else {
                        msg.error("Read Only! This magmachamber was already built!");
                    }
                }
                msg.info("Magmachamber "+magmaChamber.name+"'s readius = "+magmaChamber.baseRadius);
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
