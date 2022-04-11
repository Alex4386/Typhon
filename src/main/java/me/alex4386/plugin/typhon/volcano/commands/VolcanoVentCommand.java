package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonCommand;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class VolcanoVentCommand {
    VolcanoVent vent;
    boolean isMainVent;

    public VolcanoVentCommand(VolcanoVent vent) {
        this.vent = vent;
        this.isMainVent = vent.equals(vent.volcano.mainVent);
    }

    public VolcanoVentCommand(VolcanoVent vent, boolean isMainVent) {
        this.vent = vent;
        this.isMainVent = isMainVent;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {

        // vol name mainVent command
        // -1  0    1          2

        // vol name subVent name command
        // -1  0    1         2    3

        int baseOffset = 2 + (isMainVent ? 0 : 1);

        if (args.length > baseOffset) {
            String operationName = args[baseOffset];

            if (args.length == baseOffset + 1) {
                return TyphonCommand.search(operationName, VolcanoVentCommandAction.listAll(sender));
            } else if (args.length >= baseOffset + 2) {
                VolcanoVentCommandAction action = VolcanoVentCommandAction.getAction(operationName);

                if (action != null) {
                    if (action == VolcanoVentCommandAction.ERUPT || action == VolcanoVentCommandAction.LAVA_FLOW) {
                        if (args.length == baseOffset + 2) {
                            String[] res = {"start", "stop", "now"};
                            return Arrays.asList(res);
                        } else if (args.length == baseOffset + 3) {
                            if (args[baseOffset + 1].toLowerCase().equals("now")) {
                                String[] res = {"<? count>"};
                                return Arrays.asList(res);
                            }
                        }
                    } else if (action == VolcanoVentCommandAction.CONFIG) {
                        if (args.length == baseOffset + 2) {
                            String[] configNodes = {
                                    "lavaflow:delay",
                                    "lavaflow:flowed",
                                    "bombs:launchPower:min",
                                    "bombs:launchPower:max",
                                    "bombs:explosionPower:min",
                                    "bombs:explosionPower:max",
                                    "bombs:radius:min",
                                    "bombs:radius:max",
                                    "bombs:delay",
                                    "erupt:delay",
                                    "erupt:bombs:min",
                                    "erupt:bombs:max",
                                    "erupt:explosion:size",
                                    "erupt:explosion:damagingSize",
                                    "vent:craterRadius",
                                    "vent:type",
                                    "vent:fissureLength",
                                    "vent:fissureAngle",
                            };

                            return TyphonCommand.search(args[baseOffset + 1], Arrays.asList(configNodes));

                        } else if (args.length == baseOffset + 3) {
                            String[] res = { "<? value>" };
                            return Arrays.asList(res);
                        }
                    } else if (action == VolcanoVentCommandAction.TREMOR) {
                        if (args.length == baseOffset + 2) {
                            String[] res = { "<? power>" };
                            return Arrays.asList(res);
                        }
                    } else if (action == VolcanoVentCommandAction.STATUS) {
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];
                            List<String> searchResults = new ArrayList<>();
                            for (VolcanoVentStatus status : VolcanoVentStatus.values()) {
                                if (status.name().startsWith(searchQuery)) {
                                    searchResults.add(status.name());
                                }
                            }
                            return searchResults;
                        }
                    }
                }
            }
        }

        return null;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String[] newArgs = VolcanoCommandUtils.parseSubmenuCommand("subVent", args);

        String operationName = "";
        operationName = newArgs.length > 0 ? newArgs[0] : "info";
        VolcanoVentCommandAction action = VolcanoVentCommandAction.getAction(operationName);

        VolcanoMessage msg = new VolcanoMessage(this.vent.volcano, sender);

        if (action == null) {
            msg.error("Invalid Operation: "+operationName);
            return true;
        }

        if (!action.hasPermission(sender)) {
            msg.error("You don't have enough permission to run "+action.getCommand());
            return true;
        }


        switch (action) {
            case START:
                vent.start();
                msg.info("Vent "+vent.getName()+" is now started!");
                break;
            case STOP:
                vent.stop();
                msg.info("Vent "+vent.getName()+" is now stopped!");
                break;
            case TREMOR:
                if (newArgs.length == 1) {
                    msg.info("Creating tremor at "+vent.name);
                    vent.tremor.runTremorCycle();
                } else if (newArgs.length == 2) {
                    double power = Double.parseDouble(newArgs[1]);
                    vent.tremor.showTremorActivity(TyphonUtils.getHighestRocklikes(vent.location.getBlock()), power);
                    msg.info("Creating tremor at "+vent.name+" with power: "+power);
                }
                break;
            case ERUPT:
                if (newArgs.length == 1) {
                    msg.info("Vent "+vent.name+" is "+(vent.isErupting() ? "" : "not ")+"erupting now!");
                } else {
                    if (newArgs[1].equalsIgnoreCase("start")) {

                        if (vent.isErupting()) {
                            msg.warn("Vent "+vent.getName()+" is already erupting!");
                            break;
                        }
                        vent.startErupting();
                        msg.info("Vent "+vent.getName()+" is now erupting!");

                    } else if (newArgs[1].equalsIgnoreCase("stop")) {
                        if (!vent.isErupting()) {
                            msg.warn("Vent "+vent.getName()+" is not erupting!");
                            break;
                        }
                        vent.stopErupting();
                        msg.info("Vent "+vent.getName()+" is now stopped erupting!");

                    } else if (newArgs[1].equalsIgnoreCase("now")) {
                        if (newArgs.length == 3) {
                            int bombCount = Integer.parseInt(newArgs[2]);
                            vent.erupt(bombCount);
                            msg.info("Vent "+vent.getName()+" is erupting "+bombCount+" bombs now!");
                        } else {
                            vent.erupt();
                            msg.info("Vent "+vent.getName()+" is erupting now!");
                        }
                    }
                }
                break;
            case SUMMIT:
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Vent Summit] "+ChatColor.GOLD+"Summit of vent "+vent.name);

                VolcanoCommandUtils.findSummitAndSendToSender(sender, this.vent);
                break;
            case HELP:
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcano Vent Command Manual");
                sender.sendMessage(VolcanoVentCommandAction.getAllManual(sender, label, this.vent.volcano.name, vent.name));
                break;
            case DELETE:
                if (vent.isMainVent()) {
                    msg.error("Since this vent is main vent, you should delete the entire volcano. instead of deleting this.");
                } else {
                    vent.stop();
                    vent.shutdown();
                    vent.volcano.subVents.remove(vent.name);
                    msg.info("Vent "+vent.name+" has been deleted!");
                }
                break;
            case LAVA_FLOW:
                if (newArgs.length == 1) {
                    msg.info("Vent "+vent.name+" is "+(vent.isErupting() ? "" : "not ")+"flowing lava right now!");
                } else {
                    if (newArgs[1].equalsIgnoreCase("start")) {

                        if (vent.isFlowingLava()) {
                            msg.warn("Vent "+vent.getName()+" is already flowing lava!");
                            break;
                        }
                        vent.startFlowingLava();
                        msg.info("Vent "+vent.getName()+" is now flowing lava!");

                    } else if (newArgs[1].equalsIgnoreCase("stop")) {
                        if (!vent.isFlowingLava()) {
                            msg.warn("Vent "+vent.getName()+" is not flowing lava!");
                            break;
                        }
                        vent.stopFlowingLava();
                        msg.info("Vent "+vent.getName()+" is now stopped flowing lava!");

                    } else if (newArgs[1].equalsIgnoreCase("now")) {
                        if (newArgs.length == 3) {
                            int flowAmount = Integer.parseInt(newArgs[2]);
                            for (int i = 0; i < flowAmount; i++) {
                                vent.lavaFlow.flowLava();
                            }
                            msg.info("Vent "+vent.getName()+" is flowing "+flowAmount+" blocks of lava now!");
                        } else {
                            vent.lavaFlow.flowLava();
                            msg.info("Vent "+vent.getName()+" is erupting now!");
                        }
                    }
                }
                break;
            case QUICK_COOL:
                vent.lavaFlow.cooldownAll();
                vent.bombs.shutdown();
                msg.info("Cooled down all lava from vent "+vent.getName());
                break;
            case STATUS:
                if (newArgs.length == 2) {
                    VolcanoVentStatus prevStatus = vent.status;
                    VolcanoVentStatus status = VolcanoVentStatus.getStatus(newArgs[1]);

                    if (status != null) {
                        vent.status = status;
                        if (prevStatus == VolcanoVentStatus.ERUPTING && status != VolcanoVentStatus.ERUPTING) {
                            vent.stop();
                        } else if (prevStatus != VolcanoVentStatus.ERUPTING && status == VolcanoVentStatus.ERUPTING) {
                            vent.start();
                        }
                    }
                }
                msg.info("Vent Status: "+vent.volcano.manager.getVentChatColor(vent)+vent.status.toString());
                break;

            case CONFIG:
                if (newArgs.length < 2) {
                    msg.error("Invalid usage");
                    return true;
                }
                if (newArgs[1].equalsIgnoreCase("lavaflow:delay")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.lavaFlow.settings.delayFlowed = Integer.parseInt(newArgs[2]);
                        msg.info("lavaflow:delay - "+ vent.lavaFlow.settings.delayFlowed+" ticks");
                    }
                } else if (newArgs[1].equalsIgnoreCase("lavaflow:flowed")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.lavaFlow.settings.flowed = Integer.parseInt(newArgs[2]);
                        msg.info("lavaflow:flowed - "+ vent.lavaFlow.settings.flowed+" ticks");
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:launchPower:min")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.bombs.minBombLaunchPower = Float.parseFloat(newArgs[2]);
                        msg.info("bombs:launchPower:min - "+ vent.bombs.minBombLaunchPower);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:launchPower:max")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.bombs.maxBombLaunchPower = Float.parseFloat(newArgs[2]);
                        msg.info("bombs:launchPower:max - "+ vent.bombs.maxBombLaunchPower);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:explosionPower:min")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.bombs.minBombPower = Float.parseFloat(newArgs[2]);
                        msg.info("bombs:explosionPower:min - "+ vent.bombs.minBombPower);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:explosionPower:max")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.bombs.maxBombPower = Float.parseFloat(newArgs[2]);
                        msg.info("bombs:explosionPower:max - "+ vent.bombs.maxBombPower);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:radius:min")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.bombs.minBombRadius = Integer.parseInt(newArgs[2]);
                        msg.info("bombs:radius:min - "+ vent.bombs.minBombRadius);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:radius:max")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.bombs.maxBombRadius = Integer.parseInt(newArgs[2]);
                        msg.info("bombs:radius:max - "+ vent.bombs.maxBombRadius);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:delay")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.bombs.bombDelay = Integer.parseInt(newArgs[2]);
                        msg.info("bombs:delay - "+ vent.bombs.bombDelay);
                    }
                } else if (newArgs[1].equalsIgnoreCase("erupt:delay")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.erupt.settings.explosionDelay = Integer.parseInt(newArgs[2]);
                        msg.info("erupt:delay - "+ vent.erupt.settings.explosionDelay);
                    }
                } else if (newArgs[1].equalsIgnoreCase("erupt:bombs:min")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.erupt.settings.minBombCount = Integer.parseInt(newArgs[2]);
                        msg.info("erupt:bombs:min - "+ vent.erupt.settings.minBombCount);
                    }
                } else if (newArgs[1].equalsIgnoreCase("erupt:bombs:max")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.erupt.settings.maxBombCount = Integer.parseInt(newArgs[2]);
                        msg.info("erupt:bombs:max - "+ vent.erupt.settings.maxBombCount);
                    }
                } else if (newArgs[1].equalsIgnoreCase("erupt:explosion:size")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.erupt.settings.explosionSize = Integer.parseInt(newArgs[2]);
                        msg.info("erupt:explosion:size - "+ vent.erupt.settings.explosionSize);
                    }
                } else if (newArgs[1].equalsIgnoreCase("erupt:explosion:damagingSize")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.erupt.settings.damagingExplosionSize = Integer.parseInt(newArgs[2]);
                        msg.info("erupt:explosion:damagingSize - "+ vent.erupt.settings.damagingExplosionSize);
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:craterRadius")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.setRadius(Integer.parseInt(newArgs[2]));
                            vent.cachedVentBlocks = null;
                        }

                        msg.info("vent:craterRadius - "+ vent.craterRadius);
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:fissureAngle")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.fissureAngle = Double.parseDouble(newArgs[2]);                            vent.cachedVentBlocks = null;
                            vent.cachedVentBlocks = null;
                        }
                        msg.info("vent:fissureAngle - "+ vent.fissureAngle);
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:fissureLength")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.fissureLength = Integer.parseInt(newArgs[2]);
                            vent.cachedVentBlocks = null;
                        }
                        msg.info("vent:fissureLength - "+ vent.fissureLength);
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:type")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            VolcanoVentType type = VolcanoVentType.fromString(newArgs[2]);
                            vent.setType(type);
                            vent.cachedVentBlocks = null;
                        }
                        msg.info("vent:type - "+ vent.getType().toString());
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:silicateLevel")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) vent.lavaFlow.settings.silicateLevel = Double.parseDouble(newArgs[2]);
                        msg.info("vent:silicateLevel - "+ vent.lavaFlow.settings.silicateLevel);
                    }
                } else {
                    msg.error("Invalid config node!");
                }

                vent.volcano.trySave();

                break;

            case TELEPORT:
                if (sender instanceof Entity) {
                    Entity senderEntity = (Entity)sender;
                    vent.teleport(senderEntity);
                    msg.info("You have been teleported to vent "+vent.getName()+" of Volcano "+vent.volcano.name);
                } else {
                    msg.error("This command can not be used by console.");
                }
                break;

            case INFO:
            default:
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcano Vent Info");
                msg.info("Location: "+ TyphonUtils.blockLocationTostring(vent.location.getBlock()));
                msg.info("Summit  : "+ TyphonUtils.blockLocationTostring(vent.getSummitBlock()));
                msg.info("LavaFlow: "+ vent.isFlowingLava()+" @ "+String.format("%.2f",vent.longestFlowLength)+"m");
                msg.info("Erupting: "+ vent.isErupting()+" @ "+String.format("%.2f",vent.bombs.maxDistance)+"m");
                msg.info("Radius  : "+ vent.getRadius());
                msg.info("Status  : "+vent.volcano.manager.getVentChatColor(vent)+vent.status.toString());
                msg.info("C.Ejecta: "+vent.record.currentEjectaVolume+" blocks (VEI: "+TyphonUtils.getVEIScale(vent.record.currentEjectaVolume)+")");
                msg.info("Ejecta  : "+vent.record.getTotalEjecta()+" blocks (VEI: "+TyphonUtils.getVEIScale(vent.record.getTotalEjecta())+")");

                sender.sendMessage("type \"/"+label+" "+ vent.volcano.name+" "+args[1]+(args.length > 2 ? " "+args[2]:"")+" help\" for more commands.");
                break;
        }
        return true;
    }

}
