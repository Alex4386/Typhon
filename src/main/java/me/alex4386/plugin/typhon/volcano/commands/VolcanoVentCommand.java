package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonCommand;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentGenesis;
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

public class VolcanoVentCommand {
    VolcanoVent vent;
    boolean isMainVent;

    String[] configNodes = {
            "lavaflow:delay",
            "lavaflow:flowed",
            "lavaflow:silicateLevel",
            "lavaflow:gasContent",
            "bombs:explosionPower:min",
            "bombs:explosionPower:max",
            "bombs:radius:min",
            "bombs:radius:max",
            "bombs:delay",
            "erupt:style",
            "erupt:autoconfig",
            "explosion:bombs:min",
            "explosion:bombs:max",
            "explosion:scheduler:size",
            "explosion:scheduler:damagingSize",
            "vent:craterRadius",
            "vent:type",
            "vent:fissureLength",
            "vent:fissureAngle",
    };

    public VolcanoVentCommand(VolcanoVent vent) {
        this.vent = vent;
        this.isMainVent = vent.equals(vent.volcano.mainVent);
    }

    public VolcanoVentCommand(VolcanoVent vent, boolean isMainVent) {
        this.vent = vent;
        this.isMainVent = isMainVent;
    }

    public List<String> onTabComplete(
            CommandSender sender, Command command, String label, String[] args) {

        // vol name mainVent command
        // -1 0 1 2

        // vol name subVent name command
        // -1 0 1 2 3

        int baseOffset = 2 + (isMainVent ? 0 : 1);

        if (args.length > baseOffset) {
            String operationName = args[baseOffset];

            if (args.length == baseOffset + 1) {
                return TyphonCommand.search(
                        operationName, VolcanoVentCommandAction.listAll(sender));
            } else if (args.length >= baseOffset + 2) {
                VolcanoVentCommandAction action = VolcanoVentCommandAction.getAction(operationName);

                if (action != null) {
                    if (action == VolcanoVentCommandAction.CONFIG) {
                        if (args.length == baseOffset + 2) {
                            return TyphonCommand.search(
                                    args[baseOffset + 1], Arrays.asList(configNodes));
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
                    } else if (action == VolcanoVentCommandAction.STYLE) {
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];
                            List<String> searchResults = new ArrayList<>();
                            for (VolcanoEruptStyle style : VolcanoEruptStyle.values()) {
                                String name = TyphonUtils.toLowerCaseDumbEdition(style.name());
                                if (name.startsWith(searchQuery)) {
                                    searchResults.add(name);
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
            msg.error("Invalid Operation: " + operationName);
            return true;
        }

        if (!action.hasPermission(sender)) {
            msg.error("You don't have enough permission to run " + action.getCommand());
            return true;
        }

        switch (action) {
            case START:
                vent.start();
                msg.info("Vent " + vent.getName() + " is now started!");
                break;
            case STOP:
                vent.stop();
                msg.info("Vent " + vent.getName() + " is now stopped!");
                break;
            case TREMOR:
                if (newArgs.length == 1) {
                    msg.info("Creating tremor at " + vent.name);
                    vent.tremor.runTremorCycle();
                } else if (newArgs.length == 2) {
                    double power = Double.parseDouble(newArgs[1]);
                    vent.tremor.showTremorActivity(
                            TyphonUtils.getHighestRocklikes(vent.location.getBlock()), power);
                    msg.info("Creating tremor at " + vent.name + " with power: " + power);
                }
                break;
            case SUMMIT:
                sender.sendMessage(
                        ChatColor.RED
                                + ""
                                + ChatColor.BOLD
                                + "[Vent Summit] "
                                + ChatColor.GOLD
                                + "Summit of vent "
                                + vent.name);

                VolcanoCommandUtils.findSummitAndSendToSender(sender, this.vent);
                break;
            case HELP:
                sender.sendMessage(
                        ChatColor.RED
                                + ""
                                + ChatColor.BOLD
                                + "[Typhon Plugin] "
                                + ChatColor.GOLD
                                + "Volcano Vent Command Manual");
                sender.sendMessage(
                        VolcanoVentCommandAction.getAllManual(
                                sender, label, this.vent.volcano.name, vent.name));
                break;
            case CALDERA:
                if (newArgs.length != 1 && !(newArgs.length >= 2 && newArgs[1].equalsIgnoreCase("clear"))) {
                    if (vent.isCaldera()) {
                        msg.error(
                                "This vent already has caldera. If this is an error, run clear");
                        break;
                    } else if (!vent.caldera.canCreateCaldera()) {
                        msg.error(
                                "This vent is too small to create caldera.");
                        break;
                    }
                }

                if (newArgs.length == 1) {
                    long cycle = vent.caldera.cycle;
                    long current = vent.caldera.current;
                    long total = vent.caldera.total;

                    sender.sendMessage(
                            ChatColor.RED
                                    + ""
                                    + ChatColor.BOLD
                                    + "[Typhon Plugin] "
                                    + ChatColor.GOLD
                                    + "Volcano Caldera");

                    msg.info("Current Cycle #"+cycle+" - ("+current+"/"+total+")");
                } else if (newArgs.length >= 2) {
                    if (newArgs[1].equalsIgnoreCase("start")) {
                        if (!vent.caldera.isSettedUp()) {
                            msg.error(
                                    "The caldera creation settings are not configured.");
                        } else {
                            vent.caldera.startErupt();
                            msg.info("Plinian eruption has started.");
                        }
                    } else if (newArgs[1].equalsIgnoreCase("skip")) {
                        if (!vent.caldera.isSettedUp()) {
                            msg.error(
                                    "The caldera creation settings are not configured.");
                        } else {
                            vent.caldera.forceShutdown();
                            msg.info("Plinian eruption has skipped.");
                        }
                    } else if (newArgs[1].equalsIgnoreCase("clear")) {
                        vent.calderaRadius = -1;
                        vent.getVolcano().trySave();
                        msg.info("current caldera data has been cleared");
                    } else {
                        try {
                            int radius, deep, oceanY;
                            if (newArgs.length >= 2) {
                                radius = Integer.parseInt(newArgs[1]);
                                msg.info("caldera settings:");
                                msg.info("radius = "+radius);
                                if (newArgs.length == 2) {
                                    vent.caldera.autoSetup(radius);
                                    break;
                                }

                                deep = Integer.parseInt(newArgs[2]);
                                msg.info("depth = "+deep);
                                if (newArgs.length == 3) {
                                    vent.caldera.autoSetup(radius, deep);
                                    break;
                                }

                                oceanY = Integer.parseInt(newArgs[3]);
                                msg.info("oceanY = "+deep);
                                if (newArgs.length >= 4) {
                                    vent.caldera.autoSetup(radius, deep, oceanY);
                                }
                            }
                        } catch(Exception e) {
                            msg.error("Failed to parse user input.");
                        }
                    }
                }
                break;
            case DELETE:
                if (vent.isMainVent()) {
                    msg.error(
                            "Since this vent is main vent, you should delete the entire volcano."
                                    + " instead of deleting this.");
                } else {
                    vent.delete();
                    msg.info("Vent " + vent.name + " has been deleted!");
                }
                break;
            case QUICK_COOL:
                vent.lavaFlow.cooldownAll();
                vent.bombs.shutdown();
                msg.info("Cooled down all lava from vent " + vent.getName());
                break;
            case STATUS:
                if (newArgs.length == 2) {
                    VolcanoVentStatus prevStatus = vent.getStatus();
                    VolcanoVentStatus status = VolcanoVentStatus.getStatus(newArgs[1]);

                    if (status != null) {
                        vent.setStatus(status);
                        if (prevStatus == VolcanoVentStatus.ERUPTING
                                && status != VolcanoVentStatus.ERUPTING) {
                            vent.stop();
                        } else if (prevStatus != VolcanoVentStatus.ERUPTING
                                && status == VolcanoVentStatus.ERUPTING) {
                            vent.start();
                        }
                    }
                }
                msg.info(
                        "Vent Status: "
                                + vent.volcano.manager.getVentChatColor(vent)
                                + vent.getStatus().toString());
                break;
            case GENESIS:
                if (newArgs.length <= 1) {
                    msg.info(
                            "Vent Genesis Type: " +
                                    vent.genesis.getName()
                    );
                    if (vent.genesis == VolcanoVentGenesis.MONOGENETIC) {
                        msg.info(" - This vent will erupt only once.");
                    } else if (vent.genesis == VolcanoVentGenesis.POLYGENETIC) {
                        msg.info(" - This vent can erupt multiple time");
                    }
                } else if (newArgs.length >= 2) {
                    String genesisTypeRaw = newArgs[1];
                    VolcanoVentGenesis genesisType = VolcanoVentGenesis.getGenesisType(genesisTypeRaw);
                    if (genesisType == null) {
                        msg.error("Specified genesis type does not exist!");
                    } else {
                        vent.genesis = genesisType;
                        msg.info(
                                "Vent Genesis Type: " +
                                        vent.genesis.getName()
                        );
                    }
                }
                break;
            case LAVA_DOME:
                if (newArgs.length <= 1) {
                    msg.error("Invalid usage");
                    return true;
                }

                String domeAction = newArgs[1];
                if (domeAction.equalsIgnoreCase("start")) {
                    vent.stop();
                    if (vent.getStatus().getScaleFactor() < 0.5)
                        vent.setStatus(VolcanoVentStatus.MAJOR_ACTIVITY);
                    vent.lavadome.start();
                    msg.info("Lavadome eruption started.");
                } else if (domeAction.equalsIgnoreCase("stop")) {
                    vent.lavadome.stop();
                    msg.info("Lavadome eruption stopped.");
                } else if (domeAction.equalsIgnoreCase("build")) {
                    msg.info("Forcing lavadome build....");
                    vent.lavadome.build();
                    msg.info("Lavadome build complete!");
                } else if (domeAction.equalsIgnoreCase("explode")) {
                    vent.lavadome.explode();
                    vent.start();
                    msg.info(
                            "Lavadome has just exploded. Vent eruption was automatically"
                                    + " triggered.");
                }
                break;
            case STYLE:
                if (newArgs.length < 2) {
                    String isSurtsey = "";
                    if (this.vent.surtseyan.isSurtseyan()) {
                        isSurtsey = " (Surtseyan)";
                    }
                    msg.info("Vent Type: " + vent.getType());
                    msg.info("Eruption Style: " + vent.erupt.getStyle()+isSurtsey);
                    return true;
                }

                if (newArgs[1] != null) {
                    String type = newArgs[1];

                    VolcanoEruptStyle style = VolcanoEruptStyle.getVolcanoEruptStyle(type);
                    if (style != null) {
                        vent.erupt.setStyle(style);
                        msg.info(
                                "Eruption Style of Vent "
                                        + vent.getName()
                                        + " was updated to: "
                                        + style.toString());

                        vent.erupt.autoConfig();
                        return true;
                    }

                    VolcanoVentType ventType = VolcanoVentType.fromString(type);
                    if (ventType != null) {
                        vent.setType(ventType);
                        msg.info(
                                "Type of Vent "
                                        + vent.getName()
                                        + " was updated to: "
                                        + ventType.toString());

                        return true;
                    }

                    msg.error("Invalid Type: " + type);
                }

                break;
            case CONFIG:
                if (newArgs.length < 2) {
                    msg.error("Invalid usage");
                    return true;
                }
                if (newArgs[1].equalsIgnoreCase("lavaflow:delay")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.lavaFlow.settings.delayFlowed = Integer.parseInt(newArgs[2]);
                        msg.info(
                                "lavaflow:delay - "
                                        + vent.lavaFlow.settings.delayFlowed
                                        + " ticks");
                    }
                } else if (newArgs[1].equalsIgnoreCase("lavaflow:silicateLevel")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.lavaFlow.settings.silicateLevel = Math.min(0.9, Math.max(0.3, Double.parseDouble(newArgs[2])));
                        msg.info(
                                "lavaflow:silicateLevel - "
                                    + vent.lavaFlow.settings.silicateLevel
                                    + " ("
                                    + String.format("%.2f", vent.lavaFlow.settings.silicateLevel * 100)
                                    + "%)"
                        );
                    }
                } else if (newArgs[1].equalsIgnoreCase("lavaflow:gasContent")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.lavaFlow.settings.gasContent = Math.min(1.0, Math.max(0.0, Double.parseDouble(newArgs[2])));
                        msg.info(
                                "lavaflow:gasContent - "
                                    + vent.lavaFlow.settings.gasContent
                                    + " ("
                                    + String.format("%.2f", vent.lavaFlow.settings.silicateLevel * 100)
                                    + "%)"
                        );
                    }
                } else if (newArgs[1].equalsIgnoreCase("lavaflow:flowed")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.lavaFlow.settings.flowed = Integer.parseInt(newArgs[2]);
                        msg.info("lavaflow:flowed - " + vent.lavaFlow.settings.flowed + " ticks");
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:explosionPower:min")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.bombs.minBombPower = Float.parseFloat(newArgs[2]);
                        msg.info("bombs:explosionPower:min - " + vent.bombs.minBombPower);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:explosionPower:max")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.bombs.maxBombPower = Float.parseFloat(newArgs[2]);
                        msg.info("bombs:explosionPower:max - " + vent.bombs.maxBombPower);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:radius:min")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.bombs.minBombRadius = Integer.parseInt(newArgs[2]);
                        msg.info("bombs:radius:min - " + vent.bombs.minBombRadius);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:radius:max")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.bombs.maxBombRadius = Integer.parseInt(newArgs[2]);
                        msg.info("bombs:radius:max - " + vent.bombs.maxBombRadius);
                    }
                } else if (newArgs[1].equalsIgnoreCase("bombs:delay")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.bombs.bombDelay = Integer.parseInt(newArgs[2]);
                        msg.info("bombs:delay - " + vent.bombs.bombDelay);
                    }
                } else if (newArgs[1].equalsIgnoreCase("erupt:style")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            VolcanoEruptStyle style = VolcanoEruptStyle.getVolcanoEruptStyle(newArgs[2]);
                            if (style != null)
                                vent.erupt.setStyle(style);
                        }
                        msg.info("erupt:style - " + vent.erupt.getStyle().toString());
                    }
                } else if (newArgs[1].equalsIgnoreCase("erupt:autoconfig")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3 && newArgs[2].equalsIgnoreCase("confirm")) {
                            vent.erupt.autoConfig();
                            msg.info("erupt:autoconfig applied!");
                        } else {
                            msg.info("run erupt:autoconfig with confirm to apply autoconfig.");
                        }
                    }
                } else if (newArgs[1].equalsIgnoreCase("explosion:bombs:min")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.explosion.settings.minBombCount = Integer.parseInt(newArgs[2]);
                        msg.info("explosion:bombs:min - " + vent.explosion.settings.minBombCount);
                    }
                } else if (newArgs[1].equalsIgnoreCase("explosion:bombs:max")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.explosion.settings.maxBombCount = Integer.parseInt(newArgs[2]);
                        msg.info("explosion:bombs:max - " + vent.explosion.settings.maxBombCount);
                    }
                } else if (newArgs[1].equalsIgnoreCase("explosion:scheduler:size")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.explosion.settings.explosionSize = Integer.parseInt(newArgs[2]);
                        msg.info(
                                "explosion:scheduler:size - "
                                        + vent.explosion.settings.explosionSize);
                    }
                } else if (newArgs[1].equalsIgnoreCase("explosion:scheduler:damagingSize")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.explosion.settings.damagingExplosionSize = Integer.parseInt(newArgs[2]);
                        msg.info(
                                "explosion:scheduler:damagingSize - "
                                        + vent.explosion.settings.damagingExplosionSize);
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:craterRadius")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.setRadius(Integer.parseInt(newArgs[2]));
                            vent.flushCache();
                        }

                        msg.info("vent:craterRadius - " + vent.craterRadius);
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:fissureAngle")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            if (newArgs[2].equalsIgnoreCase("get")
                                    || newArgs[2].equalsIgnoreCase("auto")) {
                                if (sender instanceof Player) {
                                    Player player = (Player) sender;

                                    float yaw = -1 * player.getLocation().getYaw();
                                    yaw = (yaw % 360 + 360) % 360;

                                    vent.fissureAngle = Math.toRadians(yaw);
                                }
                            } else {
                                vent.fissureAngle = Double.parseDouble(newArgs[2]);
                            }

                            vent.flushCache();
                        }

                        msg.info(
                                "vent:fissureAngle - "
                                        + vent.fissureAngle
                                        + " ("
                                        + Math.toDegrees(vent.fissureAngle)
                                        + " deg)");
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:fissureLength")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.fissureLength = Integer.parseInt(newArgs[2]);
                            vent.flushCache();
                        }
                        msg.info("vent:fissureLength - " + vent.fissureLength);
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:type")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            VolcanoVentType type = VolcanoVentType.fromString(newArgs[2]);
                            vent.setType(type);
                            if (sender instanceof Player) {
                                Player player = (Player) sender;

                                float yaw = player.getLocation().getYaw();
                                yaw = (yaw % 360 + 360) % 360;

                                vent.fissureAngle = Math.toRadians(yaw);
                            }
                            vent.flushCache();
                        }
                        msg.info("vent:type - " + vent.getType().toString());
                    }
                } else if (newArgs[1].equalsIgnoreCase("vent:silicateLevel")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3)
                            vent.lavaFlow.settings.silicateLevel = Double.parseDouble(newArgs[2]);
                        msg.info("vent:silicateLevel - " + vent.lavaFlow.settings.silicateLevel);
                    }
                } else {
                    msg.error("Invalid config node!");
                    msg.error("Available config nodes: " + String.join(", ", configNodes));
                }

                vent.volcano.trySave(true);

                break;

            case TELEPORT:
                if (newArgs.length >= 2) {
                    Player player = Bukkit.getPlayer(newArgs[1]);
                    if (player == null) {
                        vent.teleport((Entity) player);
                        msg.info(
                                "Player "+player.getName()+" have been teleported to vent "
                                        + vent.getName()
                                        + " of Volcano "
                                        + vent.volcano.name);
                    }
                } else {
                    if (sender instanceof Entity) {
                        Entity senderEntity = (Entity) sender;
                        vent.teleport(senderEntity);
                        msg.info(
                                "You have been teleported to vent "
                                        + vent.getName()
                                        + " of Volcano "
                                        + vent.volcano.name);
                    } else {
                        msg.error("This command can not be used by console without specifying player name");
                    }    
                }
                break;

            case INFO:
            default:
                sender.sendMessage(
                        ChatColor.RED
                                + ""
                                + ChatColor.BOLD
                                + "[Typhon Plugin] "
                                + ChatColor.GOLD
                                + "Volcano Vent Info");

                msg.info(
                        "Location: " + TyphonUtils.blockLocationTostring(vent.location.getBlock()));
                msg.info("Summit  : " + TyphonUtils.blockLocationTostring(vent.getSummitBlock()));
                msg.info(
                        "Eruption: "
                                + (vent.erupt.isErupting()
                                        ? ChatColor.RED + "true"
                                        : ChatColor.AQUA + "false"));
                msg.info(" - Style: " + (vent.erupt.getStyle().toString()));
                msg.info(
                        " - Lava : "
                                + vent.isFlowingLava()
                                + " @ "
                                + String.format("%.2f", vent.longestFlowLength)
                                + "m");
                msg.info(
                        " - Bomb : "
                                + vent.isExploding()
                                + " @ "
                                + String.format("%.2f", vent.bombs.maxDistance)
                                + "m");
                msg.info("Radius  : " + vent.getRadius());
                msg.info(
                        "Status  : "
                                + vent.volcano.manager.getVentChatColor(vent)
                                + vent.getStatus().toString());
                msg.info(
                        "C.Ejecta: "
                                + vent.record.currentEjectaVolume
                                + " blocks (VEI: "
                                + TyphonUtils.getVEIScale(vent.record.currentEjectaVolume)
                                + ")");
                msg.info(
                        "Ejecta  : "
                                + vent.record.getTotalEjecta()
                                + " blocks (VEI: "
                                + TyphonUtils.getVEIScale(vent.record.getTotalEjecta())
                                + ")");

                msg.info(
                        "Caldera  : "
                                + (vent.isCaldera() ? vent.calderaRadius : "None"));

                sender.sendMessage(
                        "type \"/"
                                + label
                                + " "
                                + vent.volcano.name
                                + " "
                                + args[1]
                                + (args.length > 2 ? " " + args[2] : "")
                                + " help\" for more commands.");
                break;
        }
        return true;
    }
}
