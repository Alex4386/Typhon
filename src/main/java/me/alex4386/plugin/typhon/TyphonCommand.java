package me.alex4386.plugin.typhon;

import me.alex4386.plugin.typhon.volcano.commands.VolcanoCommand;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.commands.VolcanoCraterCommand;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoConstructionStatus;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.IOException;
import java.util.*;


public class TyphonCommand {

    public static void createVolcano(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("volcano.create")) {
            TyphonMessage.error(sender, "You don't have enough permission!");
            return;
        }

        if (args.length >= 2) {
            String volcanoName = args[1];
            File volcanoDir = new File(TyphonPlugin.volcanoDir, volcanoName);

            if (TyphonPlugin.listVolcanoes.get(volcanoName) == null) {

                if (!(sender instanceof Player)) {
                    TyphonMessage.error(sender, "Unable to generate "+volcanoName+" from console!");
                    return;
                }

                Location location = ((Player) sender).getLocation();

                try {
                    Volcano volcano = new Volcano(volcanoDir.toPath(), location);
                    volcano.load();

                    TyphonPlugin.listVolcanoes.put(volcanoName, volcano);
                    TyphonMessage.info(sender, "Volcano "+volcanoName+" was generated!");

                } catch (IOException e) {
                    TyphonMessage.error(sender, "I/O Exception was generated during creation of Volcano "+volcanoName+"!");
                    e.printStackTrace();
                } catch (ParseException e) {
                    TyphonMessage.error(sender, "JSON Parsing Exception was generated during creation of Volcano "+volcanoName+"!");
                }
            } else {
                TyphonMessage.error(sender, "Volcano "+volcanoName+" already exists!");
            }

        } else {
            TyphonMessage.error(sender, "Invalid Command! Usage: /typhon create <name>");
        }
    }


    public static void nearVolcano(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("volcano.near")) {
            TyphonMessage.error(sender, "You don't have enough permission!");
            return;
        }

        if (sender instanceof Player) {
            Player player = (Player) sender;
            Location location = player.getLocation();

            List<Volcano> volcanoesNearYou = new ArrayList<>();

            for (Volcano volcano: TyphonPlugin.listVolcanoes.values()) {
                if (volcano.manager.isInAnyCrater(location)) {
                    // yes you are near.
                    volcanoesNearYou.add(volcano);
                }
            }

            sender.sendMessage(ChatColor.DARK_RED+""+ChatColor.BOLD+"[Near-by Volcanoes]");
            if (volcanoesNearYou.size() != 0) {
                for (Volcano volcano: volcanoesNearYou) {
                    sender.sendMessage(ChatColor.DARK_RED+" - "+volcano.manager.getChatColor()+volcano.name);
                }
            }

        } else {
            TyphonMessage.error(sender, "This command can not be triggered from console.");
        }
    }

    public static void showConstructions(CommandSender sender, Command command, String label, String[] args) {
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

            sender.sendMessage(ChatColor.DARK_RED+""+ChatColor.BOLD+"[Constructions of Volcano "+volcano.name+"]");
        } else {
            statuses = TyphonPlugin.constructionStatuses;
            sender.sendMessage(ChatColor.DARK_RED+""+ChatColor.BOLD+"[Volcano Constructions]");
        }

        for (VolcanoConstructionStatus status : statuses) {
            if (volcano == null) sender.sendMessage(ChatColor.GOLD + "Volcano " + status.volcano.name);
            sender.sendMessage(ChatColor.YELLOW + status.jobName + ChatColor.GRAY + ": " + status.currentStage + "/" + status.totalStages + " (" + String.format("%.2f", status.currentStage * 100 / (double) status.totalStages) + "%)");
            if (volcano == null) sender.sendMessage("");
        }
    }

    public static List<String> search(String key, List<String> haystack) {
        List<String> searchResult = new ArrayList<>();
        for (String word:haystack) {
            if (word.startsWith(key)) {
                searchResult.add(word);
            }
        }

        return searchResult;
    }

    public static List<String> search(String key, Set<String> haystack) {
        List<String> searchResult = new ArrayList<>();
        for (String word:haystack) {
            if (word.startsWith(key)) {
                searchResult.add(word);
            }
        }

        return searchResult;
    }

    public static List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        String commandName = label.toLowerCase();

        if (commandName.equals("typhon")) {
            if (args.length == 1) {
                return TyphonCommand.search(args[0], TyphonCommandAction.listAll(sender));
            } else if (args.length == 2) {
                TyphonCommandAction action = TyphonCommandAction.getAction(args[0]);

                if (action.equals(TyphonCommandAction.CONSTRUCTIONS)) {
                    return searchVolcano(args[1]);
                } else if (action.equals(TyphonCommandAction.CREATE)) {
                    String[] str = { "<name>" };
                    return Arrays.asList(str);
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


    public static boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                    default:
                        break;
                }
            } else {
                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"v."+TyphonPlugin.version);
                sender.sendMessage(ChatColor.GRAY+"Developed by Alex4386");
                sender.sendMessage(ChatColor.GRAY+"Originally developed by diwaly");
                sender.sendMessage(ChatColor.GRAY+"Distributed under GPLv3");
                sender.sendMessage("");
                sender.sendMessage(ChatColor.YELLOW+"/typhon create <name>"+ChatColor.GRAY+" : Create a volcano");
                sender.sendMessage(ChatColor.YELLOW+"/typhon near"+ChatColor.GRAY+" : get near-by volcanoes");
            }
        } else if (commandName.equals("volcano") || commandName.equals("vol")) {
            if (args.length >= 1) {

                Volcano volcano = TyphonPlugin.listVolcanoes.get(args[0]);
                if (volcano != null) {
                    VolcanoCommand cmd = new VolcanoCommand(volcano);
                    return cmd.onCommand(sender, command, label, args);
                } else {
                    TyphonMessage.error(sender, "Volcano "+args[0]+" was not found!");
                }

            } else {
                if (TyphonCommand.hasPermission(sender, "volcano.list")) {
                    sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcanoes");

                    if (TyphonPlugin.listVolcanoes.size() == 0) {
                        sender.sendMessage(ChatColor.GRAY+"No Volcano found");

                    } else {
                        for (Map.Entry<String, Volcano> entry: TyphonPlugin.listVolcanoes.entrySet()) {
                            String name = entry.getKey();
                            Volcano volcano = entry.getValue();
                            sender.sendMessage(" - "+volcano.manager.getChatColor()+name);
                        }
                    }

                }

            }
        }

        return true;
    }

    public static boolean hasPermission(CommandSender sender, String actionName) {
        return sender.hasPermission("typhon."+actionName);
    }

    public static List<String> searchVolcano(String key) {
        return me.alex4386.plugin.typhon.TyphonCommand.search(key, TyphonPlugin.listVolcanoes.keySet());
    }
}
