package me.alex4386.plugin.typhon;

import java.io.File;
import java.io.IOException;
import java.util.*;

import me.alex4386.plugin.typhon.gaia.TyphonGaia;
import me.alex4386.plugin.typhon.gaia.TyphonGaiaCommand;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.commands.VolcanoCommand;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoConstructionStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.json.simple.parser.ParseException;

public class TyphonCommand {

    public static void createVolcano(
            CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("volcano.create")) {
            TyphonMessage.error(sender, "You don't have enough permission!");
            return;
        }

        if (!(sender instanceof Player)) {
            TyphonMessage.error(
                    sender, "Unable to generate volcano from console!");
            return;
        }

        Location location = ((Player) sender).getLocation();

        if (args.length == 1) {
            sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Create Volcano]");
            sender.sendMessage("Gaia is generating adequate volcano at your location.");

            Volcano volcano = TyphonGaia.spawnVolcano(location);

            if (volcano == null) {
                TyphonMessage.error(
                        sender, "Gaia has failed to generate adequate volcano!");
                return;
            }

            sender.sendMessage(ChatColor.DARK_RED + "New volcano has been generated!");
            sender.sendMessage(ChatColor.DARK_RED + "Name : " + volcano.name);
            sender.sendMessage(ChatColor.DARK_RED + "Vent : " + volcano.mainVent.getType().toString());
            sender.sendMessage(ChatColor.DARK_RED + "Style: " + volcano.mainVent.erupt.getStyle().toString());
            sender.sendMessage(ChatColor.DARK_RED + "SiO2%: " + String.format("%.2f", volcano.mainVent.lavaFlow.settings.silicateLevel)+"%");
        } else if (args.length >= 2) {
            String volcanoName = args[1];
            File volcanoDir = new File(TyphonPlugin.volcanoDir, volcanoName);

            if (TyphonPlugin.listVolcanoes.get(volcanoName) == null) {
                try {
                    Volcano volcano = new Volcano(volcanoDir.toPath(), location);
                    volcano.load();

                    if (args.length >= 3) {
                        VolcanoEruptStyle style = VolcanoEruptStyle.getVolcanoEruptStyle(args[2]);

                        if (style == null) {
                            TyphonMessage.warn(
                                    sender,
                                    "Erupt Style "
                                            + args[2]
                                            + " is not valid! skipping configuration.");
                        } else {
                            volcano.mainVent.erupt.setStyle(style);
                            volcano.mainVent.erupt.autoConfig();

                            if (style == VolcanoEruptStyle.HAWAIIAN) {
                                if (sender instanceof Player) {
                                    Player player = (Player) sender;

                                    float yaw = -1 * player.getLocation().getYaw();
                                    yaw = (yaw % 360 + 360) % 360;

                                    volcano.mainVent.fissureAngle = Math.toRadians(yaw);

                                    TyphonMessage.info(
                                            sender,
                                            "Fissure angle was setted to your current viewing"
                                                    + " direction");
                                } else {
                                    volcano.mainVent.fissureAngle = Math.random() * Math.PI * 2;
                                }

                                volcano.mainVent.setType(VolcanoVentType.FISSURE);
                            }
                        }
                    } else if (args.length == 2) {
                        // Fallback mode to Diwaly's volcano plugin style.
                        volcano.mainVent.erupt.setStyle(VolcanoEruptStyle.HAWAIIAN);
                        volcano.mainVent.setType(VolcanoVentType.CRATER);
                    }

                    volcano.trySave();

                    TyphonPlugin.listVolcanoes.put(volcanoName, volcano);
                    TyphonMessage.info(sender, "Volcano " + volcanoName + " was generated!");

                    TyphonBlueMapUtils.addVolcanoOnMap(volcano);

                } catch (IOException e) {
                    TyphonMessage.error(
                            sender,
                            "I/O Exception was generated during creation of Volcano "
                                    + volcanoName
                                    + "!");
                    e.printStackTrace();
                } catch (ParseException e) {
                    TyphonMessage.error(
                            sender,
                            "JSON Parsing Exception was generated during creation of Volcano "
                                    + volcanoName
                                    + "!");
                }
            } else {
                TyphonMessage.error(sender, "Volcano " + volcanoName + " already exists!");
            }
        }
    }

    public static void nearVolcano(
            CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("volcano.near")) {
            TyphonMessage.error(sender, "You don't have enough permission!");
            return;
        }

        if (sender instanceof Player) {
            Player player = (Player) sender;
            Location location = player.getLocation();

            List<Volcano> volcanoesNearYou = new ArrayList<>();
            List<VolcanoVent> ventsNearYou = new ArrayList<>();

            for (Volcano volcano : TyphonPlugin.listVolcanoes.values()) {
                if (volcano.manager.isInAnyBombAffected(location)
                        || volcano.manager.isInAnyLavaFlow(location)) {
                    // yes you are near.
                    volcanoesNearYou.add(volcano);
                }
            }

            for (Volcano volcano : volcanoesNearYou) {
                for (VolcanoVent vent : volcano.manager.getVents()) {
                    if (vent.getTwoDimensionalDistance(location)
                            <= vent.craterRadius + vent.longestFlowLength + 100) {
                        if (vent.isBombAffected(location) || vent.isInLavaFlow(location)) {
                            ventsNearYou.add(vent);
                        }
                    }
                }
            }

            sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Near-by Volcanoes]");
            if (volcanoesNearYou.size() != 0) {
                for (Volcano volcano : volcanoesNearYou) {
                    sender.sendMessage(
                            ChatColor.DARK_RED
                                    + " - "
                                    + volcano.manager.getVolcanoChatColor()
                                    + volcano.name);
                }
            }
            sender.sendMessage("");

            sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Near-by Vents]");
            if (volcanoesNearYou.size() != 0) {
                for (VolcanoVent vent : ventsNearYou) {
                    sender.sendMessage(
                            ChatColor.DARK_RED
                                    + " - "
                                    + vent.volcano.manager.getVentChatColor(vent)
                                    + (vent.name == null ? "main" : vent.name)
                                    + " from "
                                    + vent.volcano.name
                                    + " : "
                                    + String.format(
                                            "%.2f", vent.getTwoDimensionalDistance(location))
                                    + "m");
                    if (vent.isBombAffected(location)) {
                        sender.sendMessage(
                                "   -> Bombs Affected   : "
                                        + String.format("%.2f", vent.bombs.maxDistance)
                                        + "m");
                        sender.sendMessage(
                                "   -> LavaFlow Affected: "
                                        + String.format("%.2f", vent.longestFlowLength)
                                        + "m");
                    }
                }
            }
            sender.sendMessage("");

        } else {
            TyphonMessage.error(sender, "This command can not be triggered from console.");
        }
    }

    public static String[] getDebugCommands(CommandSender sender) {
        String[] blank = {};
        if (sender.hasPermission("typhon.debug")) {
            String[] commands = {};

            return commands;
        }
        return blank;
    }

    public static void showConstructions(
            CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("volcano.constructions")) {
            TyphonMessage.error(sender, "You don't have enough permission!");
            return;
        }

        Volcano volcano = null;
        if (args.length == 2) {
            String volcanoName = args[1];
            volcano = TyphonPlugin.listVolcanoes.get(volcanoName);
        }

        List<VolcanoConstructionStatus> statuses = new ArrayList<>();

        if (volcano != null) {
            for (VolcanoConstructionStatus status : TyphonPlugin.constructionStatuses) {
                if (status.volcano.name.equals(volcano.name)) {
                    statuses.add(status);
                }
            }

            sender.sendMessage(
                    ChatColor.DARK_RED
                            + ""
                            + ChatColor.BOLD
                            + "[Constructions of Volcano "
                            + volcano.name
                            + "]");
        } else {
            statuses = TyphonPlugin.constructionStatuses;
            sender.sendMessage(
                    ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Volcano Constructions]");
        }

        for (VolcanoConstructionStatus status : statuses) {
            if (volcano == null)
                sender.sendMessage(ChatColor.GOLD + "Volcano " + status.volcano.name);
            sender.sendMessage(
                    ChatColor.YELLOW
                            + status.jobName
                            + ChatColor.GRAY
                            + ": "
                            + status.currentStage
                            + "/"
                            + status.totalStages
                            + " ("
                            + String.format(
                                    "%.2f", status.currentStage * 100 / (double) status.totalStages)
                            + "%)");
            if (status.hasSubStage) {
                sender.sendMessage(
                        ChatColor.GOLD
                                + "SubStage: "
                                + status.currentSubStage
                                + "/"
                                + status.totalSubStage
                                + " ("
                                + String.format(
                                        "%.2f",
                                        status.currentSubStage
                                                * 100
                                                / (double) status.totalSubStage)
                                + "%)");
            }
            if (volcano == null) sender.sendMessage("");
        }
    }

    public static List<String> search(String key, List<String> haystack) {
        List<String> searchResult = new ArrayList<>();
        for (String word : haystack) {
            if (word.startsWith(key)) {
                searchResult.add(word);
            }
        }

        return searchResult;
    }

    public static List<String> search(String key, Set<String> haystack) {
        List<String> searchResult = new ArrayList<>();
        for (String word : haystack) {
            if (word.startsWith(key)) {
                searchResult.add(word);
            }
        }

        return searchResult;
    }

    public static List<String> onTabComplete(
            CommandSender sender, Command command, String label, String[] args) {
        String commandName = label.toLowerCase();

        if (commandName.equals("typhon")) {
            if (args.length == 1) {
                return TyphonCommand.search(args[0], TyphonCommandAction.listAll(sender));
            } else if (args.length >= 2) {
                TyphonCommandAction action = TyphonCommandAction.getAction(args[0]);
                if (action == null) return null;

                if (args.length >= 2) {
                    if (action.equals(TyphonCommandAction.CONSTRUCTIONS)) {
                        if (args.length == 2) return searchVolcano(args[1]);
                    } else if (action.equals(TyphonCommandAction.CREATE)) {
                        if (args.length == 2) {
                            String[] str = {"<name>"};
                            return Arrays.asList(str);
                        } else if (args.length == 3) {
                            List<String> str = new ArrayList<>();
                            for (VolcanoEruptStyle style : VolcanoEruptStyle.values()) {
                                str.add(style.toString());
                            }
                            return search(args[2], str);
                        }
                    } else if (action.equals(TyphonCommandAction.DEBUG)) {
                        if (args.length == 2) {
                            if (TyphonDebugCommand.canRunDebug(sender)) {
                                return TyphonDebugCommand.onTabComplete(
                                        sender, TyphonDebugCommand.convertToDebugNewArgs(args));
                            }
                        }
                    }
                }
            }
        } else if (commandName.equals("volcano") || commandName.equals("vol")) {
            if (args.length > 0) {
                if (args.length == 1) {
                    if (hasPermission(sender, "volcano.list")) {
                        return searchVolcano(args[0]);
                    } else {
                        return null;
                    }
                } else {
                    Volcano volcano = TyphonPlugin.listVolcanoes.get(args[0]);
                    if (volcano != null) {
                        VolcanoCommand cmd = new VolcanoCommand(volcano);
                        return cmd.onTabComplete(sender, command, label, args);
                    }
                }
            }
        }

        return null;
    }

    public static boolean onCommand(
            CommandSender sender, Command command, String label, String[] args) {
        String commandName = label.toLowerCase();

        if (commandName.equals("typhon")) {
            if (args.length >= 1) {
                TyphonCommandAction action = TyphonCommandAction.getAction(args[0]);
                switch (action) {
                    case CREATE:
                        createVolcano(sender, command, label, args);
                        break;
                    case NEAR:
                        nearVolcano(sender, command, label, args);
                        break;
                    case CONSTRUCTIONS:
                        showConstructions(sender, command, label, args);
                        break;
                    case GAIA:
                        return TyphonGaiaCommand.onCommand(sender, command, label, args);
                    case DEBUG:
                        if (TyphonDebugCommand.canRunDebug(sender)) {
                            return TyphonDebugCommand.onCommand(
                                    sender, TyphonDebugCommand.convertToDebugNewArgs(args));
                        }
                        break;
                    case SUCCESSOR: {
                        if (!sender.hasPermission("typhon.successor")) {
                            TyphonMessage.error(sender, "You don't have enough permission!");
                            return true;
                        }

                        if (!(sender instanceof Player)) {
                            TyphonMessage.error(sender, "This command can not be triggered from console.");
                            return true;
                        }
                        Player player = (Player) sender;

                        if (args.length == 1) {
                            boolean isEnabled = TyphonToolEvents.successorEnabled.containsKey(sender);
                            TyphonMessage.info(
                                    sender,
                                    "Successor tool is "
                                            + (isEnabled ? "enabled" : "disabled")
                                            + " for you.");
                        } else if (args.length == 2) {
                            String subCommand = args[1];
                            if (subCommand.equalsIgnoreCase("enable")) {
                                ItemStack stack = new ItemStack(Material.WOODEN_SHOVEL);

                                ItemMeta meta = stack.getItemMeta();
                                if (meta != null) {
                                    meta.setItemName("Successor");
                                    meta.setRarity(ItemRarity.EPIC);
                                    meta.setDisplayName("Successor");
                                    meta.setLore(
                                            Arrays.asList(
                                                    "Primary Successor Tool",
                                                    "Use this tool to run primary succession on the ground."));
                                    stack.setItemMeta(meta);
                                }

                                player.getInventory().addItem(stack);
                                TyphonToolEvents.registerSuccessor(player);
                                TyphonMessage.info(sender, "Successor tool has been given to you.");
                            } else if (subCommand.equalsIgnoreCase("disable")) {
                                TyphonToolEvents.unregisterSuccessor(player);
                                TyphonMessage.info(sender, "Successor tool has been disabled for you.");
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
            } else {
                sender.sendMessage(
                        ChatColor.RED
                                + ""
                                + ChatColor.BOLD
                                + "[Typhon Plugin] "
                                + ChatColor.GOLD
                                + "v."
                                + TyphonPlugin.version);
                sender.sendMessage(ChatColor.GRAY + "Developed by Alex4386");
                sender.sendMessage(ChatColor.GRAY + "Originally developed by diwaly");
                sender.sendMessage(ChatColor.GRAY + "Distributed under GPLv3");
                sender.sendMessage("");
                sender.sendMessage(
                        ChatColor.YELLOW
                                + "/typhon create <name> <?style>"
                                + ChatColor.GRAY
                                + " : Create a volcano");
                sender.sendMessage(
                        ChatColor.YELLOW
                                + "/typhon near"
                                + ChatColor.GRAY
                                + " : get near-by volcanoes");
            }
        } else if (commandName.equals("volcano") || commandName.equals("vol")) {
            if (args.length >= 1) {

                Volcano volcano = TyphonPlugin.listVolcanoes.get(args[0]);
                if (volcano != null) {
                    VolcanoCommand cmd = new VolcanoCommand(volcano);
                    return cmd.onCommand(sender, command, label, args);
                } else {
                    TyphonMessage.error(sender, "Volcano " + args[0] + " was not found!");
                }

            } else {
                if (TyphonCommand.hasPermission(sender, "volcano.list")) {
                    sender.sendMessage(
                            ChatColor.RED
                                    + ""
                                    + ChatColor.BOLD
                                    + "[Typhon Plugin] "
                                    + ChatColor.GOLD
                                    + "Volcanoes");

                    if (TyphonPlugin.listVolcanoes.size() == 0) {
                        sender.sendMessage(ChatColor.GRAY + "No Volcano found");

                    } else {
                        for (Map.Entry<String, Volcano> entry :
                                TyphonPlugin.listVolcanoes.entrySet()) {
                            String name = entry.getKey();
                            Volcano volcano = entry.getValue();
                            sender.sendMessage(
                                    " - " + volcano.manager.getVolcanoChatColor() + name);
                        }
                    }
                }
            }
        }

        return true;
    }

    public static boolean hasPermission(CommandSender sender, String actionName) {
        return sender.hasPermission("typhon." + actionName);
    }

    public static List<String> searchVolcano(String key) {
        return me.alex4386.plugin.typhon.TyphonCommand.search(
                key, TyphonPlugin.listVolcanoes.keySet());
    }
}
