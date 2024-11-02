package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.util.*;

public class VolcanoCommand {
    Volcano volcano;

    public VolcanoCommand(Volcano volcano) {
        this.volcano = volcano;
    }

    public VolcanoCommand(String string) throws ClassNotFoundException {
        Volcano volcano = TyphonPlugin.listVolcanoes.get(string);
        if (volcano == null)
            throw new ClassNotFoundException();
        this.volcano = volcano;
    }

    public List<String> onTabComplete(
            CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2) {
            String operationName = args[1];
            if (args.length == 2) {
                return TyphonCommand.search(operationName, VolcanoCommandAction.listAll(sender));
            } else if (args.length >= 3) {
                VolcanoCommandAction action = VolcanoCommandAction.getAction(operationName);
                if (action != null) {
                    if (action.equals(VolcanoCommandAction.SUB_VENT)) {
                        if (args.length == 3) {
                            // vent name selection
                            String query = args[2];
                            if (TyphonCommand.hasPermission(sender, "vent.list")) {
                                return TyphonCommand.search(query, volcano.subVents.keySet());
                            }
                        } else if (args.length >= 4) {
                            // vent operation
                            VolcanoVent vent = volcano.subVents.get(args[2]);
                            if (vent != null) {
                                VolcanoVentCommand cmd = new VolcanoVentCommand(vent, false);
                                return cmd.onTabComplete(sender, command, label, args);
                            }
                        }
                    } else if (action.equals(VolcanoCommandAction.AUTO_START)) {
                        String[] values = { "enable", "disable" };
                        return Arrays.asList(values.clone());
                    } else if (action.equals(VolcanoCommandAction.MAIN_VENT)) {
                        // vent operation
                        VolcanoVent vent = volcano.mainVent;
                        if (vent != null) {
                            VolcanoVentCommand cmd = new VolcanoVentCommand(vent, true);
                            return cmd.onTabComplete(sender, command, label, args);
                        }
                    } else if (action.equals(VolcanoCommandAction.CREATE)) {
                        if (args.length == 3) {
                            String[] types = { "crater", "fissure", "flank", "autovent" };
                            return Arrays.asList(types.clone());
                        } else if (args.length > 3) {
                            String option = args[2];
                            if (option.toLowerCase().equals("crater")) {
                                String[] result = { "<name>" };
                                return Arrays.asList(result);
                            } else if (option.toLowerCase().equals("flank")) {
                                if (args.length == 4) {
                                    String[] result = { "<name>" };
                                    return Arrays.asList(result);
                                }
                            } else if (option.toLowerCase().equals("autovent")) {
                                if (args.length == 4) {
                                    String[] result = { "<playerName>" };
                                    return Arrays.asList(result);
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2) {
            String operationName = args[1];
            VolcanoCommandAction action = VolcanoCommandAction.getAction(operationName);
            VolcanoMessage msg = new VolcanoMessage(this.volcano, sender);

            VolcanoVentCommand ventCmd = null;

            if (action != null) {
                if (action.hasPermission(sender)) {
                    switch (action) {
                        case START:
                            msg.warn(
                                    sender,
                                    "Using Volcano's default start/stop has been deprecated.");
                            msg.warn(
                                    sender,
                                    "Please, Use /volcano "
                                            + this.volcano.name
                                            + " mainVent start");
                            this.volcano.start();
                            msg.info(sender, "Volcano " + this.volcano.name + " has started!");
                            break;
                        case STOP:
                            msg.warn(
                                    sender,
                                    "Using Volcano's default start/stop has been deprecated.");
                            msg.warn(
                                    sender,
                                    "Please, Use /volcano " + this.volcano.name + " mainVent stop");
                            this.volcano.stop();
                            msg.info(sender, "Volcano " + this.volcano.name + " has stopped!");
                            break;
                        case RENAME:
                        {
                            String prevName = this.volcano.name;
                            try {
                                String newName = args[2];
                                this.volcano.rename(newName);
                            } catch(IOException e) {
                                msg.error(
                                        sender,
                                        "Volcano " + prevName + " has failed to rename!");
                                e.printStackTrace();
                            }
                        }
                        case DELETE:
                            try {
                                this.volcano.delete();
                                msg.info(
                                        sender,
                                        "Volcano " + this.volcano.name + " has been deleted!");
                            } catch (IOException e) {
                                msg.error(
                                        sender,
                                        "Volcano " + this.volcano.name + " has failed to delete!");
                                e.printStackTrace();
                            }
                            break;
                        case SHUTDOWN:
                            volcano.shutdown();
                            msg.info(
                                    sender,
                                    "Volcano " + this.volcano.name + " has been shut down!");
                            break;
                        case QUICK_COOL:
                            volcano.quickCool();
                            msg.info(
                                    sender,
                                    "Volcano "
                                            + this.volcano.name
                                            + " has cooled all flowing lava!");
                            break;
                        case AUTO_START: {
                            if (args.length == 2) {
                                msg.info(
                                        "AutoStart: "
                                                + (this.volcano.autoStart.canAutoStart ? "enabled" : "disabled"));
                            } else {
                                String value = args[2];
                                if (value.equalsIgnoreCase("enable")) {
                                    this.volcano.autoStart.canAutoStart = true;
                                    msg.info("AutoStart has been enabled!");
                                } else if (value.equalsIgnoreCase("disable")) {
                                    this.volcano.autoStart.canAutoStart = false;
                                    msg.info("AutoStart has been disabled!");
                                }
                            }
                        }
                        case SUCCESSION:
                            if (args.length >= 3) {

                                if (args[2].equals("trigger")) {
                                    if (sender instanceof Player player) {
                                        this.volcano.succession.runSuccession(player.getLocation().getBlock());
                                        msg.info("Primary Succession has been triggered at your location");

                                    } else {
                                        msg.error("This command can not be used by console.");
                                    }
                                    break;
                                } else {
                                    boolean state = Boolean.parseBoolean(args[2]);
                                    this.volcano.succession.setEnabled(state);

                                    if (state) {
                                        msg.info("Primary Succession has been enabled!");
                                    } else {
                                        msg.info("Primary Succession has been disabled!");
                                    }
                                }
                            } else {
                                msg.info(
                                        "Primary Succession: "
                                                + (this.volcano.succession.isEnabled() ? "enabled" : "disabled")
                                );
                            }
                            break;
                        case TELEPORT:
                            if (sender instanceof Entity) {
                                Entity senderEntity = (Entity) sender;
                                volcano.mainVent.teleport(senderEntity);
                                msg.info(
                                        "You have been teleported to mainVent of Volcano "
                                                + volcano.name);
                            } else {
                                msg.error("This command can not be used by console.");
                            }
                            break;
                        case NEAR:
                            if (sender instanceof Player) {
                                Player player = ((Player) sender);
                                Location location = player.getLocation();

                                VolcanoVent nearestVent = volcano.manager.getNearestVent(location);
                                msg.info(
                                        "Nearest Vent: "
                                                + nearestVent.getName()
                                                + " @ "
                                                + String.format(
                                                        "%.2f",
                                                        nearestVent.getTwoDimensionalDistance(
                                                                location))
                                                + "m");
                                msg.info(
                                        "Status  : "
                                                + (volcano.manager.getVentChatColor(nearestVent)
                                                        + nearestVent.getStatus().toString()));
                                msg.info(
                                        "LavaFlow: "
                                                + (volcano.manager.isInAnyLavaFlow(location)
                                                        ? ChatColor.RED + "Affected"
                                                        : "Not Affected"));
                                msg.info(
                                        "Bombs   : "
                                                + (volcano.manager.isInAnyBombAffected(location)
                                                        ? ChatColor.RED + "Affected"
                                                        : "Not Affected"));

                            } else {
                                msg.error("This command can not be used by console.");
                            }
                            break;
                        case CREATE:
                            if (args.length >= 4) {

                                // vol wa create dike aaa
                                //      0      1    2   3

                                String type = args[2];
                                String name = args[3];

                                if (sender instanceof Player) {
                                    Player player = (Player) sender;
                                    if (type.equalsIgnoreCase("crater")) {
                                        if (this.volcano.subVents.get(name) == null) {
                                            VolcanoVent vent = new VolcanoVent(
                                                    volcano, player.getLocation(), name);
                                            vent.setType(VolcanoVentType.CRATER);

                                            this.volcano.subVents.put(name, vent);
                                            vent.initialize();
                                            msg.info("Vent " + vent.name + " has been created!");
                                        } else {
                                            msg.error(
                                                    sender,
                                                    "Vent "
                                                            + name
                                                            + " already exists on Volcano "
                                                            + this.volcano.name
                                                            + "!");
                                        }
                                    } else if (type.equalsIgnoreCase("fissure")) {
                                        if (this.volcano.subVents.get(name) == null) {
                                            VolcanoVent vent = new VolcanoVent(
                                                    volcano, player.getLocation(), name);
                                            vent.setType(VolcanoVentType.FISSURE);
                                            vent.lavaFlow.settings.silicateLevel = 0.45;

                                            this.volcano.subVents.put(name, vent);

                                            vent.initialize();
                                            msg.info("Vent " + vent.name + " has been created!");
                                        } else {
                                            msg.error(
                                                    sender,
                                                    "Vent "
                                                            + name
                                                            + " already exists on Volcano "
                                                            + this.volcano.name
                                                            + "!");
                                        }
                                    } else if (type.equalsIgnoreCase("flank")) {
                                        if (this.volcano.subVents.get(name) == null) {
                                            if (this.volcano.autoStart.canDoFlankEruption()) {
                                                VolcanoVent vent = this.volcano.mainVent.erupt.openFissure();
                                                if (vent != null) {
                                                    vent.shutdown();
                                                    this.volcano.subVents.remove(vent.name);
                                                    this.volcano.subVents.put(name, vent);
                                                    vent.initialize();
                                                    msg.info("Vent " + vent.name + " has been created!");
                                                } else {
                                                    msg.error(
                                                            sender,
                                                            "Failed to generate flank eruption vent "
                                                                    + name
                                                                    + " on Volcano "
                                                                    + this.volcano.name
                                                                    + "! Try it again.");
                                                }
                                            } else {
                                                msg.error(
                                                        sender,
                                                                "Volcano "
                                                                + this.volcano.name
                                                                + " is incapable of creating flank eruption!");
                                            }
                                        } else {
                                            msg.error(
                                                    sender,
                                                    "Vent "
                                                            + name
                                                            + " already exists on Volcano "
                                                            + this.volcano.name
                                                            + "!");
                                        }
                                    }
                                }

                            } else {
                                msg.error(
                                        sender,
                                        "Not enough arguments for command " + action.getCommand());
                                msg.error(
                                        sender,
                                        ""
                                                + ChatColor.RED
                                                + ChatColor.BOLD
                                                + "Usage: "
                                                + ChatColor.RESET
                                                + "/vol "
                                                + volcano.name
                                                + " create "
                                                + ChatColor.YELLOW
                                                + "<crater | fissure>"
                                                + ChatColor.GRAY
                                                + " <name> ...");
                            }
                            break;

                        case RECORD:
                            msg.info(
                                    "C.Ejecta: "
                                            + volcano.manager.getCurrentEjecta()
                                            + " blocks (VEI: "
                                            + TyphonUtils.getVEIScale(
                                                    volcano.manager.getCurrentEjecta())
                                            + ")");
                            msg.info(
                                    "Ejecta  : "
                                            + volcano.manager.getTotalEjecta()
                                            + " blocks (VEI: "
                                            + TyphonUtils.getVEIScale(
                                                    volcano.manager.getTotalEjecta())
                                            + ")");

                            break;

                        case MAIN_VENT:
                            ventCmd = new VolcanoVentCommand(volcano.mainVent, true);
                            return ventCmd.onCommand(sender, command, label, args);

                        case SUB_VENT:
                            if (args.length >= 3) {
                                String subVentName = args[2];
                                VolcanoVent subVent = volcano.subVents.get(subVentName);

                                if (subVent != null) {
                                    subVent.name = subVentName;
                                    ventCmd = new VolcanoVentCommand(subVent, false);
                                    return ventCmd.onCommand(sender, command, label, args);
                                } else {
                                    msg.error(
                                            sender,
                                            "Subvent "
                                                    + subVentName
                                                    + " doesn't exist on volcano "
                                                    + volcano.name);
                                }
                            } else {
                                sender.sendMessage(
                                        ChatColor.RED
                                                + ""
                                                + ChatColor.BOLD
                                                + "[Typhon Plugin] "
                                                + ChatColor.GOLD
                                                + "Volcano Vents");

                                for (Map.Entry<String, VolcanoVent> subVent : volcano.subVents.entrySet()) {
                                    String ventName = subVent.getKey();
                                    VolcanoVent vent = subVent.getValue();

                                    boolean isExploding = vent.isExploding(); // YELLOW
                                    boolean isFlowing = vent.isFlowingLava(); // RED

                                    ChatColor ventState = isFlowing && isExploding
                                            ? ChatColor.GOLD
                                            : (isFlowing
                                                    ? ChatColor.RED
                                                    : (isExploding
                                                            ? ChatColor.YELLOW
                                                            : ChatColor.RESET));

                                    sender.sendMessage(
                                            " - "
                                                    + ventState
                                                    + ventName
                                                    + ChatColor.RESET
                                                    + ": "
                                                    + (volcano.manager.getVentChatColor(vent)
                                                            + vent.getStatus().toString()));
                                }
                            }
                            break;
                        case UPDATE_RATE:
                            if (args.length == 3) {
                                volcano.updateRate = Integer.parseInt(args[2]);
                                volcano.shutdown(true);
                                volcano.startup();
                            }
                            msg.info(
                                    "Volcano "
                                            + volcano.name
                                            + "'s updaterate = "
                                            + volcano.updateRate
                                            + " ticks.");
                            break;
                        case SUMMIT:
                            sender.sendMessage(
                                    ChatColor.RED
                                            + ""
                                            + ChatColor.BOLD
                                            + "[Typhon Plugin] "
                                            + ChatColor.GOLD
                                            + "Volcano Summit of "
                                            + this.volcano.name);
                            VolcanoVent summitVent = this.volcano.manager.getSummitVent();
                            VolcanoCommandUtils.findSummitAndSendToSender(sender, summitVent);
                            break;
                        case DIKE:
                            msg.error("Implementation in progress...");
                            break;
                        case STATUS:
                            VolcanoVent vent = volcano.manager.getHighestStatusVent();
                            msg.info("Highest Status: " + vent.getStatus().name());
                            break;
                        case HEAT:
                            if (sender instanceof Player) {
                                Player player = (Player) sender;
                                msg.info(
                                        "Heat value of current location: "
                                                + this.volcano.manager.getHeatValue(
                                                        player.getLocation()));
                            } else {
                                msg.error("This command is built for in-game only.");
                            }
                            break;
                        case RELOAD:
                            volcano.shutdown();
                            try {
                                volcano.load();
                                volcano.startup();
                            } catch (IOException | ParseException e) {
                                msg.error("Error occurred while reloading!");
                            }
                            break;
                        case DEBUG:
                            if (args.length == 3) {
                                this.volcano.isDebug = Boolean.parseBoolean(args[2]);
                            }
                            msg.info("isDebug - " + this.volcano.isDebug);

                            break;
                        case SAVE:
                            try {
                                sender.sendMessage(
                                        ChatColor.RED
                                                + ""
                                                + ChatColor.BOLD
                                                + "[Typhon Plugin] "
                                                + ChatColor.GOLD
                                                + "Saving Volcano: "
                                                + this.volcano.name);
                                volcano.save(true);
                                sender.sendMessage(
                                        ChatColor.RED
                                                + ""
                                                + ChatColor.BOLD
                                                + "[Typhon Plugin] "
                                                + ChatColor.GOLD
                                                + "Saved Volcano: "
                                                + this.volcano.name);
                            } catch (IOException e) {
                                sender.sendMessage(
                                        ChatColor.RED
                                                + ""
                                                + ChatColor.BOLD
                                                + "[Typhon Plugin] "
                                                + ChatColor.GOLD
                                                + "Error while saving volcano: "
                                                + this.volcano.name);
                            }

                            break;
                    }
                } else {
                    msg.error(
                            sender,
                            "You don't have enough permission to run " + action.getCommand());
                }
            } else {
                msg.error(sender, "Invalid command: " + operationName);
            }
        } else {
            sender.sendMessage(
                    ChatColor.RED
                            + ""
                            + ChatColor.BOLD
                            + "[Typhon Plugin] "
                            + ChatColor.GOLD
                            + "Volcano Command Manual");

            String manuals = VolcanoCommandAction.getAllManual(sender, label, this.volcano.name);
            sender.sendMessage(manuals);
        }

        return true;
    }
}
