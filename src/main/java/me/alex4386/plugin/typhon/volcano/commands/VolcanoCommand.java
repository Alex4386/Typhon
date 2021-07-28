package me.alex4386.plugin.typhon.volcano.commands;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCraterStatus;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoDike;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMagmaChamber;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
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
        if (volcano == null) throw new ClassNotFoundException();
        this.volcano = volcano;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2) {
            String operationName = args[1];
            if (args.length == 2) {
                return TyphonCommand.search(operationName, VolcanoCommandAction.listAll(sender));
            } else if (args.length >= 3) {
                VolcanoCommandAction action = VolcanoCommandAction.getAction(operationName);
                if (action != null) {
                    if (action.equals(VolcanoCommandAction.SUB_CRATER)) {
                        if (args.length == 3) {
                            // crater name selection
                            String query = args[2];
                            if (TyphonCommand.hasPermission(sender, "crater.list")) {
                                return TyphonCommand.search(query, volcano.subCraters.keySet());
                            }
                        } else if (args.length >= 4) {
                            // crater operation
                            VolcanoCrater crater = volcano.subCraters.get(args[2]);
                            if (crater != null) {
                                VolcanoCraterCommand cmd = new VolcanoCraterCommand(crater, false);
                                return cmd.onTabComplete(sender, command, label, args);
                            }
                        }
                    } else if (action.equals(VolcanoCommandAction.MAIN_CRATER)) {
                        // crater operation
                        VolcanoCrater crater = volcano.mainCrater;
                        if (crater != null) {
                            VolcanoCraterCommand cmd = new VolcanoCraterCommand(crater, true);
                            return cmd.onTabComplete(sender, command, label, args);
                        }
                    } else if (action.equals(VolcanoCommandAction.CREATE)) {
                        if (args.length == 3) {
                            String[] types = { "crater", "dike", "magmachamber", "autocrater" };
                            return Arrays.asList(types.clone());
                        } else if (args.length > 3) {
                            String option = args[2];
                            if (option.toLowerCase().equals("crater")) {
                                String[] result = { "<name>" };
                                return Arrays.asList(result);
                            } else if (option.toLowerCase().equals("dike")) {
                                if (args.length == 4) {
                                    String[] result = { "<name>" };
                                    return Arrays.asList(result);
                                } else if (args.length == 5) {
                                    String searchQuery = args[4];
                                    return searchMagmaChamberNames(searchQuery);
                                } else if (args.length == 6) {
                                    String[] result = { "<radius>" };
                                    return Arrays.asList(result);
                                }
                            } else if (option.toLowerCase().equals("magmachamber")) {
                                if (args.length == 4) {

                                    String[] result = { "<name>" };
                                    return Arrays.asList(result);
                                } else if (args.length == 5) {
                                    String[] result = { "<baseY>" };
                                    return Arrays.asList(result);
                                } else if (args.length == 6) {
                                    String[] result = { "<baseRadius>" };
                                    return Arrays.asList(result);
                                } else if (args.length == 7) {
                                    String[] result = { "<height>" };
                                    return Arrays.asList(result);
                                }
                            } else if (option.toLowerCase().equals("autocrater")) {
                                if (args.length == 4) {
                                    String[] result = {"<playerName>"};
                                    return Arrays.asList(result);
                                }
                            }
                        }
                    } else if (action.equals(VolcanoCommandAction.MAGMA_CHAMBER)) {
                        if (args.length == 3) {
                            String searchQuery = args[2];
                            return searchMagmaChamberNames(searchQuery);
                        } else if (args.length > 3) {
                            String magmaChamberName = args[2];
                            VolcanoMagmaChamber magmaChamber = volcano.magmaIntrusion.magmaChambers.get(magmaChamberName);

                            if (magmaChamber != null) {
                                VolcanoMagmaChamberCommand magmaChamberCommand = new VolcanoMagmaChamberCommand(magmaChamber);
                                return magmaChamberCommand.onTabComplete(sender, command, label, args);
                            } else {
                                String[] result = { "invalid" };
                                return Arrays.asList(result);
                            }
                        }
                    }

                }
            }
        }
        return null;
    }

    private List<String> searchMagmaChamberNames(String searchQuery) {
        Set<String> magmaChambers = volcano.magmaIntrusion.magmaChambers.keySet();
        List<String> searchResult = new ArrayList<>();

        for (String magmaChamber : magmaChambers) {
            if (magmaChamber.startsWith(searchQuery)) {
                searchResult.add(magmaChamber);
            }
        }

        return searchResult;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2) {
            String operationName = args[1];
            VolcanoCommandAction action = VolcanoCommandAction.getAction(operationName);
            VolcanoMessage msg = new VolcanoMessage(this.volcano, sender);

            VolcanoCraterCommand craterCmd = null;
            VolcanoMagmaChamberCommand magmaChamberCmd = null;

            if (action != null) {
                if (action.hasPermission(sender)) {
                    switch (action) {
                        case START:
                            msg.warn(sender, "Using Volcano's default start/stop has been deprecated.");
                            msg.warn(sender, "Please, Use /volcano "+this.volcano.name+" mainCrater start");
                            this.volcano.start();
                            msg.info(sender, "Volcano "+this.volcano.name+" has started!");
                            break;
                        case STOP:
                            msg.warn(sender, "Using Volcano's default start/stop has been deprecated.");
                            msg.warn(sender, "Please, Use /volcano "+this.volcano.name+" mainCrater stop");
                            this.volcano.stop();
                            msg.info(sender, "Volcano "+this.volcano.name+" has stopped!");
                            break;
                        case DELETE:
                            try {
                                this.volcano.delete();
                                msg.info(sender, "Volcano "+this.volcano.name+" has been deleted!");
                            } catch (IOException e) {
                                msg.error(sender, "Volcano "+this.volcano.name+" has failed to delete!");
                                e.printStackTrace();
                            }
                            break;
                        case SHUTDOWN:
                            volcano.shutdown();
                            msg.info(sender, "Volcano "+this.volcano.name+" has been shut down!");
                            break;
                        case QUICK_COOL:
                            volcano.quickCool();
                            msg.info(sender, "Volcano "+this.volcano.name+" has cooled all flowing lava!");
                            break;
                        case TELEPORT:
                            if (sender instanceof Entity) {
                                Entity senderEntity = (Entity)sender;
                                volcano.mainCrater.teleport(senderEntity);
                                msg.info("You have been teleported to mainCrater of Volcano "+volcano.name);
                            } else {
                                msg.error("This command can not be used by console.");
                            }
                            break;
                        case NEAR:
                            if (sender instanceof Player) {
                                Player player = ((Player) sender);
                                Location location = player.getLocation();

                                VolcanoCrater nearestCrater = volcano.manager.getNearestCrater(location);
                                msg.info("Nearest Crater: "+nearestCrater.getName()+" @ "+String.format("%.2f", nearestCrater.getTwoDimensionalDistance(location))+"m");
                                msg.info("Status  : "+(volcano.manager.getCraterChatColor(nearestCrater)+nearestCrater.status.toString()));
                                msg.info("LavaFlow: "+(volcano.manager.isInAnyLavaFlow(location) ? ChatColor.RED+"Affected" : "Not Affected"));
                                msg.info("Bombs   : "+(volcano.manager.isInAnyBombAffected(location) ? ChatColor.RED+"Affected" : "Not Affected"));

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
                                    Player player = (Player)sender;
                                    if (type.equalsIgnoreCase("crater")) {
                                        if (this.volcano.subCraters.get(name) == null) {
                                            VolcanoCrater crater = new VolcanoCrater(volcano, player.getLocation(), name);
                                            this.volcano.subCraters.put(name, crater);
                                            crater.initialize();
                                            msg.info("Crater "+crater.name+" has been created!");
                                        } else {
                                            msg.error(sender, "Crater "+name+" already exists on Volcano "+this.volcano.name+"!");
                                        }
                                    } else if (type.equalsIgnoreCase("dike")) {

                                        // vol wa create dike aaa magmachamber_name radius
                                        //      0      1    2   3                 4      5

                                        if (args.length >= 5) {
                                            if (this.volcano.magmaIntrusion.dikes.get(name) == null) {
                                                String magmaChamberName = args[4];
                                                if (this.volcano.magmaIntrusion.magmaChambers.get(magmaChamberName) == null) {
                                                    VolcanoMagmaChamber magmaChamber = this.volcano.magmaIntrusion.magmaChambers.get(magmaChamberName);

                                                    Block baseBlock = magmaChamber.getMagmaDikeBaseBlock(player.getLocation());

                                                    int radius = 1;
                                                    if (args.length >= 6) { radius = Integer.parseInt(args[5]); }

                                                    if (baseBlock != null) {
                                                        VolcanoDike dike = new VolcanoDike(this.volcano, magmaChamber, baseBlock, radius);
                                                        this.volcano.magmaIntrusion.dikes.put(name, dike);
                                                        msg.info(sender, "Dike "+name+" from MagmaChamber "+magmaChamberName+" has been generated!");
                                                    } else {
                                                        msg.error(sender, "You are not in the MagmaChamber "+magmaChamberName+"'s range!");
                                                    }

                                                } else {
                                                    msg.error(sender, "MagmaChamber "+magmaChamberName+" does NOT exist on Volcano "+this.volcano.name+"!");
                                                }
                                            } else {
                                                msg.error(sender, "Dike "+name+" already exists on Volcano "+this.volcano.name+"!");
                                            }
                                        } else {
                                            msg.error(sender, "Not enough arguments to generate dikes");
                                            msg.error(sender, ""+ChatColor.RED+ChatColor.BOLD+"Usage: "+ChatColor.RESET+"/vol "+volcano.name+" create dike "+ChatColor.YELLOW+"<name> <magmaChamberName> <radius>");
                                        }
                                    } else if (type.equalsIgnoreCase("magmachamber")) {

                                        // vol wa create magmachamber aaa baseY baseradius height
                                        //      0      1            2   3     4          5      6

                                        if (args.length >= 7) {
                                            Location location = player.getLocation();

                                            int x = location.getBlockX();
                                            int y = Integer.parseInt(args[4]);
                                            int z = location.getBlockZ();

                                            int baseRadius = Integer.parseInt(args[5]);
                                            int height = Integer.parseInt(args[6]);

                                            Location baseLocation = new Location(
                                                    location.getWorld(),
                                                    x,
                                                    y,
                                                    z
                                            );
                                            Block baseBlock = baseLocation.getBlock();

                                            if (this.volcano.magmaIntrusion.magmaChambers.get(name) == null) {
                                                VolcanoMagmaChamber magmaChamber = new VolcanoMagmaChamber(this.volcano, name, baseBlock, baseRadius, height);
                                                this.volcano.magmaIntrusion.magmaChambers.put(name, magmaChamber);
                                                msg.info(sender, "MagmaChamber "+name+" has been generated!");

                                            } else {
                                                msg.error(sender, "MagmaChamber "+name+" already exist on Volcano "+this.volcano.name+"!");
                                            }

                                        } else {
                                            msg.error(sender, "Not enough arguments to generate magma chamber");
                                            msg.error(sender, ""+ChatColor.RED+ChatColor.BOLD+"Usage: "+ChatColor.RESET+"/vol "+volcano.name+" create magmachamber "+ChatColor.YELLOW+"<name> <baseY> <baseRadius> <height>");
                                        }
                                    } else if (type.equalsIgnoreCase("autocrater")) {
                                        if (args.length >= 5) {
                                            String playerName = args[4];
                                            Player target = Bukkit.getPlayer(playerName);

                                            if (target == null) {
                                                msg.error(sender, "Target player was not found!");
                                                break;
                                            }

                                            VolcanoCrater crater = this.volcano.autoStart.autoStartCreateSubCrater(player);
                                            msg.info("subcrater "+crater.getName()+" is generated near "+target.getName());
                                        } else if (args.length == 4) {
                                            VolcanoCrater crater = this.volcano.autoStart.autoStartCreateSubCrater(player);
                                            msg.info("subcrater "+crater.getName()+" is generated");
                                        }
                                    }
                                }

                            } else {
                                msg.error(sender, "Not enough arguments for command "+action.getCommand());
                                msg.error(sender, ""+ChatColor.RED+ChatColor.BOLD+"Usage: "+ChatColor.RESET+"/vol "+volcano.name+" create "+ChatColor.YELLOW+"<crater | dike | magmachamber>"+ChatColor.GRAY+" <name> ...");
                            }
                            break;

                        case RECORD:
                            msg.info("C.Ejecta: "+volcano.manager.getCurrentEjecta()+" blocks (VEI: "+TyphonUtils.getVEIScale(volcano.manager.getCurrentEjecta())+")");
                            msg.info("Ejecta  : "+volcano.manager.getTotalEjecta()+" blocks (VEI: "+TyphonUtils.getVEIScale(volcano.manager.getTotalEjecta())+")");

                            break;


                        case MAIN_CRATER:
                            craterCmd = new VolcanoCraterCommand(volcano.mainCrater, true);
                            return craterCmd.onCommand(sender, command, label, args);

                        case SUB_CRATER:
                            if (args.length >= 3) {
                                String subCraterName = args[2];
                                VolcanoCrater subCrater = volcano.subCraters.get(subCraterName);

                                if (subCrater != null) {
                                    subCrater.name = subCraterName;
                                    craterCmd = new VolcanoCraterCommand(subCrater, false);
                                    return craterCmd.onCommand(sender, command, label, args);
                                } else {
                                    msg.error(sender, "Subcrater "+subCraterName+" doesn't exist on volcano "+volcano.name);
                                }
                            } else {
                                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcano Craters");
                                sender.sendMessage(ChatColor.RED+"Red: "+ChatColor.RESET+"Lava Flows, "+ChatColor.YELLOW+"Yellow: "+ChatColor.RESET+"Eruption, "+ChatColor.GOLD+"Gold: "+ChatColor.RESET+"Both");

                                for (Map.Entry<String, VolcanoCrater> subCrater: volcano.subCraters.entrySet()) {
                                    String craterName = subCrater.getKey();
                                    VolcanoCrater crater = subCrater.getValue();

                                    boolean isErupting = crater.isErupting(); // YELLOW
                                    boolean isFlowing = crater.isFlowingLava(); // RED

                                    ChatColor craterState = isFlowing && isErupting ?
                                            ChatColor.GOLD :
                                            (
                                                    isFlowing ?
                                                            ChatColor.RED :
                                                            (
                                                                    isErupting ?
                                                                            ChatColor.YELLOW :
                                                                            ChatColor.RESET
                                                            )
                                            );

                                    sender.sendMessage(" - "+craterState+craterName+ChatColor.RESET+": "+(volcano.manager.getCraterChatColor(crater)+crater.status.toString()));
                                }
                            }
                            break;
                        case UPDATE_RATE:
                            if (args.length == 3) {
                                volcano.updateRate = Integer.parseInt(args[2]);
                                volcano.shutdown(true);
                                volcano.startup();

                            }
                            msg.info("Volcano "+volcano.name+"'s updaterate = "+volcano.updateRate+" ticks.");
                            break;
                        case SUMMIT:
                            sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcano Summit of "+this.volcano.name);
                            VolcanoCrater summitCrater = this.volcano.manager.getSummitCrater();
                            VolcanoCommandUtils.findSummitAndSendToSender(sender, summitCrater);
                            break;
                        case DIKE:
                            msg.error("Implementation in progress...");
                            break;
                        case STATUS:
                            VolcanoCrater crater = volcano.manager.getHighestStatusCrater();
                            msg.info("Highest Status: "+crater.status.name());
                            break;
                        case HEAT:
                            if (sender instanceof Player) {
                                Player player = (Player) sender;
                                msg.info("Heat value of current location: "+this.volcano.manager.getHeatValue(player.getLocation()));
                            } else {
                                msg.error("This command is built for in-game only.");
                            }
                            break;
                        case RELOAD:
                            volcano.shutdown();
                            try {
                                volcano.load();
                                volcano.startup();
                            } catch(IOException| ParseException e) {
                                msg.error("Error occurred while reloading!");
                            }
                            break;
                        case MAGMA_CHAMBER:
                            if (args.length >= 3) {
                                String magmaChamberName = args[2];
                                VolcanoMagmaChamber magmaChamber = volcano.magmaIntrusion.magmaChambers.get(magmaChamberName);

                                if (magmaChamber != null) {
                                    magmaChamberCmd = new VolcanoMagmaChamberCommand(magmaChamber);
                                    return magmaChamberCmd.onCommand(sender, command, label, args);
                                } else {
                                    msg.error(sender, "magma chamber "+magmaChamberName+" doesn't exist on volcano "+volcano.name);
                                }
                            } else {
                                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Magma Chamber");
                                sender.sendMessage(ChatColor.RED+"Red: "+ChatColor.RESET+"filled, "+ChatColor.YELLOW+"Yellow: "+ChatColor.RESET+"built, "+ChatColor.GOLD+"Gold: "+ChatColor.RESET+"Both");

                                for (Map.Entry<String, VolcanoMagmaChamber> chamberEntry: volcano.magmaIntrusion.magmaChambers.entrySet()) {
                                    String chamberName = chamberEntry.getKey();
                                    VolcanoMagmaChamber chamber = chamberEntry.getValue();

                                    boolean isFilled = chamber.isFilled;
                                    boolean isBuilt = chamber.isBuilt; // RED

                                    ChatColor chamberState = isBuilt && isFilled ?
                                            ChatColor.GOLD :
                                            (
                                                    isFilled ?
                                                            ChatColor.RED :
                                                            (
                                                                    isBuilt ?
                                                                            ChatColor.YELLOW :
                                                                            ChatColor.RESET
                                                            )
                                            );

                                    sender.sendMessage(" - "+chamberState+chamberName);
                                }
                            }
                            break;
                        case DEBUG:
                            if (args.length == 3) {
                                this.volcano.isDebug = Boolean.parseBoolean(args[2]);
                            }
                            msg.info("isDebug - "+this.volcano.isDebug);

                            break;
                        case SAVE:
                            try {
                                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Saving Volcano: "+this.volcano.name);
                                volcano.save();
                                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Saved Volcano: "+this.volcano.name);
                            } catch (IOException e) {
                                sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Error while saving volcano: "+this.volcano.name);
                            }

                            break;
                    }
                } else {
                    msg.error(sender, "You don't have enough permission to run "+action.getCommand());
                }
            } else {
                msg.error(sender, "Invalid command: "+operationName);
            }
        } else {
            sender.sendMessage(ChatColor.RED+""+ChatColor.BOLD+"[Typhon Plugin] "+ChatColor.GOLD+"Volcano Command Manual");

            String manuals = VolcanoCommandAction.getAllManual(sender, label, this.volcano.name);
            sender.sendMessage(manuals);
        }

        return true;
    }
}