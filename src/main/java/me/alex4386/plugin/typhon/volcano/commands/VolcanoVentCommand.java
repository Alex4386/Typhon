package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.TyphonCommand;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.ash.VolcanoPyroclasticFlow;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.vent.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
            "bombs:baseY",
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
            "succession:enable",
            "succession:probability",
            "succession:treeProbability",
            "ash:fullPyroclasticFlowProbability",
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
                    } else if (action == VolcanoVentCommandAction.PYROCLAST) {
                        List<String> options = new ArrayList<>();
                        if (args.length >= baseOffset + 2) {
                            String option = args[baseOffset + 1];
                            if (args.length == baseOffset + 2) {
                                if ("full".startsWith(option)) {
                                    options.add("full");
                                }
                                if (option.matches("-?\\d+(\\.\\d+)?") || option.isEmpty()) {
                                    options.add("<? count>");
                                }
                            } else if (args.length == baseOffset + 3) {
                                String value = args[baseOffset + 2];
                                if (value.matches("-?\\d+(\\.\\d+)?") || value.isEmpty()) {
                                    options.add("<? count>");
                                }
                            }
                            return options;
                        }
                    } else if (action == VolcanoVentCommandAction.STYLE) {
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];
                            List<String> searchResults = new ArrayList<>();

                            List<String> options = new ArrayList<>();
                            options.add("crater");
                            options.add("fissure");

                            for (VolcanoEruptStyle style : VolcanoEruptStyle.values()) {
                                String name = TyphonUtils.toLowerCaseDumbEdition(style.name());
                                options.add(name);
                            }

                            for (String option : options) {
                                if (option.startsWith(searchQuery)) {
                                    searchResults.add(option);
                                }
                            }
                            return searchResults;
                        }
                    } else if (action == VolcanoVentCommandAction.SUMMIT) {
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];
                            List<String> results = new ArrayList<>();

                            if ("reset".startsWith(searchQuery))
                                results.add("reset");

                            return results;
                        }
                    } else if (action == VolcanoVentCommandAction.LANDSLIDE) {
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];
                            List<String> results = new ArrayList<>();

                            if ("start".startsWith(searchQuery))
                                results.add("start");
                            if ("setAngle".startsWith(searchQuery))
                                results.add("setAngle");
                            if ("config".startsWith(searchQuery))
                                results.add("config");
                            if ("clear".startsWith(searchQuery))
                                results.add("clear");

                            return results;
                        }
                    } else if (action == VolcanoVentCommandAction.CALDERA) {
                        List<String> results = new ArrayList<>();
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];

                            if ("start".startsWith(searchQuery))
                                results.add("start");
                            if ("skip".startsWith(searchQuery))
                                results.add("skip");
                            if ("clear".startsWith(searchQuery))
                                results.add("clear");
                            if (searchQuery.matches("-?\\d+(\\.\\d+)?") || searchQuery.isEmpty()) {
                                results.add("<? radius>");
                            }

                            return results;
                        } else {
                            String radius = args[baseOffset + 1];
                            if (radius.matches("-?\\d+(\\.\\d+)?") || radius.isEmpty()) {
                                if (args.length == baseOffset + 3) {
                                    results.add("<? deep>");
                                } else if (args.length == baseOffset + 4) {
                                    results.add("<? oceanY>");
                                }

                                return results;
                            }
                        }
                    } else if (action == VolcanoVentCommandAction.GENESIS) {
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];
                            List<String> results = new ArrayList<>();

                            if ("polygenetic".startsWith(searchQuery))
                                results.add("polygenetic");
                            if ("monogenetic".startsWith(searchQuery))
                                results.add("monogenetic");

                            return results;
                        }
                    } else if (action == VolcanoVentCommandAction.LAVA_DOME) {
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];
                            List<String> results = new ArrayList<>();

                            if ("start".startsWith(searchQuery))
                                results.add("start");
                            if ("stop".startsWith(searchQuery))
                                results.add("stop");
                            if ("reset".startsWith(searchQuery))
                                results.add("reset");
                            if ("explode".startsWith(searchQuery))
                                results.add("explode");

                            return results;
                        }
                    } else if (action == VolcanoVentCommandAction.BUILDER) {
                        if (args.length == baseOffset + 2) {
                            String searchQuery = args[baseOffset + 1];
                            List<String> results = new ArrayList<>();

                            for (VolcanoVentBuilderType type : VolcanoVentBuilderType.values()) {
                                results.add(type.getName());
                            }

                            results.add("enable");
                            results.add("disable");

                            return results.stream().filter(str -> str.startsWith(searchQuery)).toList();
                        } else if (args.length >= baseOffset + 3) {
                            List<String> results = new ArrayList<>();
                            results.add("<? args"+(args.length-(baseOffset + 3))+">");
                            return results;
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
            case RESET:
                vent.reset();
                msg.info("Vent " + vent.getName() + " has been reset!");
                break;
            case BUILDER:
                if (newArgs.length >= 1) {
                    if (newArgs.length == 1) {
                        String type;
                        if (vent.builder.getType() == null) {
                            type = "Not configured";
                        } else {
                            type = vent.builder.getType().getName();
                        }

                        msg.info("Builder type: "+type);
                        msg.info("Enabled: "+(vent.builder.isRunning() ? "enabled" : "disabled"));
                        if (vent.builder.getType() != null) {
                            Map<String, String> argsMap = vent.builder.getArgumentMap();
                            if (argsMap != null) {
                                msg.info("Arguments: ");
                                for (String key : argsMap.keySet()) {
                                    msg.info(" - "+key+": "+argsMap.get(key));
                                }
                            }
                        }
                    } else {
                        String typeString = newArgs[1];
                        if (typeString.equalsIgnoreCase("enable")) {
                            if (vent.builder.getType() == null) {
                                msg.error("Builder type is not set! Please set the builder type first.");
                                return true;
                            }

                            vent.builder.setEnabled(true);
                            msg.info("Builder has been enabled");
                            return true;
                        } else if (typeString.equalsIgnoreCase("disable")) {
                            vent.builder.setEnabled(false);
                            msg.info("Builder has been disabled");
                            return true;
                        }

                        VolcanoVentBuilderType type = VolcanoVentBuilderType.fromName(typeString);

                        if (type == null) {
                            msg.error("Invalid builder type: "+typeString);
                            return true;
                        }

                        vent.builder.setType(type);
                        if (newArgs.length >= 3) {
                            String[] builderArgs = Arrays.copyOfRange(newArgs, 2, newArgs.length);
                            if (vent.builder.setArguments(builderArgs)) {
                                msg.info("Builder type has been set to "+type.getName());
                                msg.info("The builder has been enabled!");
                                vent.builder.setEnabled(true);
                            } else {
                                msg.error("Failed to set arguments for builder type "+type.getName());
                            }
                        }
                    }
                }
                break;
            case SUMMIT:
                if (newArgs.length >= 1) {
                    if (newArgs.length == 2 && newArgs[1].equalsIgnoreCase("reset")) {
                        vent.flushSummitCache();
                        sender.sendMessage(
                                ChatColor.RED
                                        + ""
                                        + ChatColor.BOLD
                                        + "[Vent Summit] "
                                        + ChatColor.GOLD
                                        + "Summit of vent "
                                        + vent.name
                                        + " has been reset.");
                    }

                    sender.sendMessage(
                            ChatColor.RED
                                    + ""
                                    + ChatColor.BOLD
                                    + "[Vent Summit] "
                                    + ChatColor.GOLD
                                    + "Summit of vent "
                                    + vent.name);

                    VolcanoCommandUtils.findSummitAndSendToSender(sender, this.vent);
                }
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
                List<String> clearActions = Arrays.asList("clear", "reset");

                if (newArgs.length != 1 && !(newArgs.length >= 2 && clearActions.contains(newArgs[1].toLowerCase()))) {
                    if (vent.isCaldera()) {
                        msg.error(
                                "This vent already has caldera. If this is an error, run clear subcommand");
                        break;
                    } else if (!vent.caldera.canCreateCaldera()) {
                        msg.error(
                                "This vent is too small to create caldera.");
                        break;
                    }
                }

                if (newArgs.length == 1) {
                    long current = vent.caldera.currentRadius;
                    long total = vent.caldera.radius;

                    sender.sendMessage(
                            ChatColor.RED
                                    + ""
                                    + ChatColor.BOLD
                                    + "[Typhon Plugin] "
                                    + ChatColor.GOLD
                                    + "Volcano Caldera");

                    msg.info("Current Cycle #"+current+" - ("+current+"/"+total+") "+String.format("%.2f", vent.caldera.getProgress() * 100)+"% Complete");
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
                            msg.info("Plinian eruption has skipped.");
                        }
                    } else if (clearActions.contains(newArgs[1].toLowerCase())) {
                        vent.calderaRadius = -1;
                        vent.getVolcano().trySave();
                        msg.info("current caldera data has been cleared");
                    } else {
                        try {
                            int radius, deep, oceanY;
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
                            msg.info("oceanY = "+oceanY);
                            if (newArgs.length >= 4) {
                                vent.caldera.autoSetup(radius, deep, oceanY);
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
                } else {
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
            case LANDSLIDE:
                if (newArgs.length <= 1) {
                    msg.info("Landslide configuration");
                    msg.info(" - Angle: " + vent.landslide.landslideAngle);
                    msg.info(" - Configured: " + vent.landslide.isConfigured());
                    return true;
                } else {
                    String landslideAction = newArgs[1];
                    if (landslideAction.equalsIgnoreCase("start")) {
                        if (!vent.landslide.isConfigured()) {
                            msg.error("Landslide is not configured yet!");
                            return true;
                        }
                        msg.info("Starting landslide...");
                        vent.landslide.start();
                    } else if (landslideAction.equalsIgnoreCase("setAngle")) {
                        if (newArgs.length >= 3) {
                            if (newArgs[2].equalsIgnoreCase("auto")) {
                                vent.landslide.landslideAngle = Math.random() * Math.PI * 2;
                            } else {
                                vent.landslide.landslideAngle = Double.parseDouble(newArgs[2]);
                            }
                        } else {
                            // get player's yaw
                            if (sender instanceof Player) {
                                Player player = (Player) sender;

                                float yaw = -1 * player.getLocation().getYaw();
                                yaw = (yaw % 360 + 360) % 360;

                                vent.landslide.landslideAngle = Math.toRadians(yaw) - Math.PI / 2;
                            } else {
                                msg.error("This command can not be used by console without specifying angle");
                                return true;
                            }
                        }
                        msg.info("Landslide angle: " + vent.landslide.landslideAngle);
                    } else if (landslideAction.equalsIgnoreCase("config")) {
                        vent.landslide.configure();
                        msg.info("Landslide data has been configured.");
                    } else if (landslideAction.equalsIgnoreCase("clear")) {
                        vent.landslide.clear();
                        msg.info("Landslide data has been cleared.");
                    }

                }
                break;
            case LAVA_DOME:
                if (newArgs.length <= 1) {
                    // show lava dome status
                    msg.info("Lava Dome configuration");
                    msg.info(" - baseY: " + vent.lavadome.baseY);
                    msg.info(" - plumbedLava: " + vent.lavadome.plumbedLava);
                    msg.info(" - baseLocation: " + TyphonUtils.blockLocationToString(vent.lavadome.baseLocation));

                    return true;
                }

                String domeAction = newArgs[1];
                if (domeAction.equalsIgnoreCase("start")) {
                    msg.info("The lavadome eruption has started.");
                    vent.erupt.setStyle(VolcanoEruptStyle.LAVA_DOME);
                    vent.start();
                } else if (domeAction.equalsIgnoreCase("stop")) {
                    msg.info("The lavadome eruption has stopped.");
                    vent.stop();
                } else if (domeAction.equalsIgnoreCase("reset")) {
                    msg.info("Resetting lavadome build args...");
                    vent.lavadome.postConeBuildHandler();
                    msg.info("Lavadome build args have been reset.");
                } else if (domeAction.equalsIgnoreCase("explode")) {
                    msg.warn("NOT IMPLEMENTED");
                }
                break;
            case SWITCH: {
                if (vent.isMainVent()) {
                    msg.error("This vent is already a main vent.");
                    return true;
                }

                String thisVentName = vent.name;
                VolcanoVent mainVent = vent.volcano.mainVent;
                mainVent.name = thisVentName;
                vent.volcano.subVents.put(thisVentName, mainVent);
                vent.volcano.mainVent = vent;
                vent.name = null;

                msg.info("Vent " + thisVentName + " has been switched to main vent.");
                vent.volcano.trySave(true);
            }
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
                                    + String.format("%.2f", vent.lavaFlow.settings.gasContent * 100)
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
                } else if (newArgs[1].equalsIgnoreCase("bombs:baseY")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            if (newArgs[2].toLowerCase().equalsIgnoreCase("reset")) {
                                vent.bombs.resetBaseY();
                            } else {
                                vent.bombs.baseY = Integer.parseInt(newArgs[2]);
                            }
                        }

                        msg.info("bombs:baseY - " + vent.bombs.baseY);
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
                } else if (newArgs[1].equalsIgnoreCase("succession:enable")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.enableSuccession = Boolean.parseBoolean(newArgs[2]);
                        }
                        msg.info("succession:enable - " + vent.enableSuccession);
                    }
                } else if (newArgs[1].equalsIgnoreCase("succession:probability")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.successionProbability = Double.parseDouble(newArgs[2]);
                        }
                        msg.info("succession:probability - " + vent.successionProbability);
                    }
                } else if (newArgs[1].equalsIgnoreCase("succession:treeProbability")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.successionTreeProbability = Double.parseDouble(newArgs[2]);
                        }
                        msg.info("succession:treeProbability - " + vent.successionTreeProbability);
                    }
                } else if (newArgs[1].equalsIgnoreCase("ash:fullPyroclasticFlowProbability")) {
                    if (newArgs.length >= 2) {
                        if (newArgs.length == 3) {
                            vent.fullPyroclasticFlowProbability = Double.parseDouble(newArgs[2]);
                        }
                        msg.info("ash:fullPyroclasticFlowProbability - " + vent.fullPyroclasticFlowProbability);
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
            case PYROCLAST:
                int count = 1;
                boolean isFull = false;
                VolcanoPyroclasticFlow flow = null;

                if (newArgs.length >= 2) {
                    String next = newArgs[1];
                    if (next.equalsIgnoreCase("full")) {
                        isFull = true;
                        if (newArgs.length >= 3) {
                            String num = newArgs[2];
                            count = Integer.parseInt(num);
                        }
                    }
                }

                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    Location loc = player.getLocation();
                    if (vent.getTwoDimensionalDistance(loc) < vent.craterRadius * 2 && count == 1) {
                        sender.sendMessage(ChatColor.RED+"Pyrocalstic flow just have spawned at your coords!");
                        flow = vent.ash.triggerPyroclasticFlow(loc.getBlock());
                    }
                }

                if (flow == null) {
                    sender.sendMessage(ChatColor.RED+"Pyrocalstic flow just have spawned at the vent "+vent.getName()+"!");

                    for (int i = 0; i < count; i++) {
                        if (vent.caldera.isForming()) {
                            flow = vent.caldera.doEruptionPyroclasticFlows();
                        } else {
                            flow = vent.ash.triggerPyroclasticFlow();
                        }

                        if (isFull) flow.setFull(true);
                    }
                } else {
                    if (isFull) {
                        flow.setFull(true);
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
                        "    Normal: "
                                + String.format("%.2f", vent.longestNormalLavaFlowLength) + "m (now: "
                                + String.format("%.2f", vent.currentNormalLavaFlowLength) + "m"
                        );

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
                        "Ejecta/s: "
                                + vent.lavaFlow.getProcessedBlocksPerSecond()
                                + " blocks/s (Unhandled: "
                                + vent.lavaFlow.unprocessedQueueBlocks()
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
