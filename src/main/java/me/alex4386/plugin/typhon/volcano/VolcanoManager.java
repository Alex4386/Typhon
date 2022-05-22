package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class VolcanoManager {
    public Volcano volcano;

    VolcanoManager(Volcano volcano) {
        this.volcano = volcano;
    }

    public List<VolcanoVent> getVents() {
        List<VolcanoVent> vents = new ArrayList<>();
        vents.add(volcano.mainVent);
        vents.addAll(volcano.subVents.values());
        return vents;
    }

    public boolean isInAnyVent(Block block) {
        return isInAnyVent(block.getLocation());
    }

    public boolean isInAnyVent(Location loc) {
        List<VolcanoVent> vents = this.getVents();

        for (VolcanoVent vent : vents) {
            if (vent.isInVent(loc)) {
                return true;
            }
        }

        return false;
    }

    public List<Player> getAffectedPlayers() {
        Collection<Player> onlinePlayers = (Collection<Player>) Bukkit.getOnlinePlayers();
        List<Player> targetPlayers = new ArrayList<>();

        for (Player player: onlinePlayers) {
            if (player instanceof Player) {
                if (volcano.manager.isInAnyLavaFlowArea(player.getLocation())) {
                    targetPlayers.add(player);
                }
            }
        }

        return targetPlayers;
    }

    public boolean isInAnyBombAffected(Location loc) {
        List<VolcanoVent> vents = this.getVents();

        for (VolcanoVent vent : vents) {
            if (vent.isBombAffected(loc)) {
                return true;
            }
        }

        return false;
    }

    public long getCurrentEjecta() {
        long total = 0;

        List<VolcanoVent> vents = this.getVents();

        for (VolcanoVent vent : vents) {
            total += vent.record.currentEjectaVolume;
        }

        return total;
    }

    public long getTotalEjecta() {
        long total = 0;

        List<VolcanoVent> vents = this.getVents();

        for (VolcanoVent vent : vents) {
            total += vent.record.getTotalEjecta();
        }

        return total;
    }

    public boolean isInAnyLavaFlow(Location loc) {
        List<VolcanoVent> vents = this.getVents();

        for (VolcanoVent vent : vents) {
            if (vent.isInLavaFlow(loc)) {
                return true;
            }
        }

        return false;
    }

    public boolean isInAnyLavaFlowOffset(Location loc, double offset) {
        List<VolcanoVent> vents = this.getVents();

        for (VolcanoVent vent : vents) {
            if (vent.getTwoDimensionalDistance(loc) <= vent.longestFlowLength + offset) {
                return true;
            }
        }

        return false;
    }

    public ChatColor getVolcanoChatColor() {
        boolean isErupting = volcano.manager.currentlyStartedVents().size() > 0;
        return (isErupting ? ChatColor.RED : (
            volcano.manager.getHighestStatusVent().status.getScaleFactor() < 0.1 ? ChatColor.GREEN : ChatColor.GOLD
        ));
    }

    public ChatColor getVentChatColor(VolcanoVent vent) {
        return (
            (vent.status == VolcanoVentStatus.ERUPTING) ? ChatColor.RED :
                (vent.status == VolcanoVentStatus.MAJOR_ACTIVITY) ? ChatColor.GOLD :
                        (vent.status == VolcanoVentStatus.MINOR_ACTIVITY) ? ChatColor.YELLOW :
                                (vent.status == VolcanoVentStatus.DORMANT) ? ChatColor.GREEN :
                                        ChatColor.RESET
        );
    }

    public VolcanoVent getNearestVent(Block block) {
        return getNearestVent(block.getLocation());
    }

    public VolcanoVent getNearestVent(Location loc) {
        List<VolcanoVent> vents = this.getVents();

        VolcanoVent nearestVent = null;
        double shortestY = -1;

        for (VolcanoVent vent : vents) {
            double distance = TyphonUtils.getTwoDimensionalDistance(loc, vent.location);
            if (shortestY < 0 || distance < shortestY) {
                shortestY = distance;
                nearestVent = vent;
            }
        }

        return nearestVent;
    }

    public VolcanoVent getSummitVent() {
        int y = -1;
        VolcanoVent summitVent = null;

        for (VolcanoVent vent:volcano.subVents.values()) {
            Block block = vent.getSummitBlock();
            int blockY = block.getY();

            if (blockY >= y) {
                y = blockY;
                summitVent = vent;
            }
        }

        Block mainVentSummit = volcano.mainVent.getSummitBlock();
        if (mainVentSummit.getY() >= y) {
            summitVent = volcano.mainVent;;
            y = mainVentSummit.getY();
        }

        return summitVent;
    }

    public Block getSummitBlock() {
        VolcanoVent summitVent = this.getSummitVent();

        return summitVent.getSummitBlock();
    }

    public boolean isInAnyLavaFlowArea(Location loc) {
        List<VolcanoVent> vents = this.getVents();

        for (VolcanoVent vent:vents) {
            if (vent.isInLavaFlow(loc)) {
                return true;
            }
        }

        return false;
    }

    public double getHeatValue(Location loc) {
        double accumulatedHeat = 0.0f;
        for (VolcanoVent vent : volcano.manager.getVents()) {
            accumulatedHeat = Math.max(vent.getHeatValue(loc), accumulatedHeat);
        }
        return Math.min(accumulatedHeat, 1.0);
    }

    public List<VolcanoVent> getVentsInRange(Location loc, double range) {
        List<VolcanoVent> list = new ArrayList<>();

        for (VolcanoVent vent : volcano.manager.getVents()) {
            if (vent.getTwoDimensionalDistance(loc) <= range) {
                list.add(vent);
            }
        }
        return list;
    }

    public VolcanoVent getHighestStatusVent() {
        VolcanoVent highestVent = volcano.mainVent;
        VolcanoVentStatus status = VolcanoVentStatus.EXTINCT;

        for (VolcanoVent vent : getVents()) {
            if (status.getScaleFactor() < vent.status.getScaleFactor()) {
                highestVent = vent;
                status = vent.status;
            }
        }

        return highestVent;
    }

    public List<VolcanoVent> getVentRadiusInRange(Location loc, double range) {
        List<VolcanoVent> list = new ArrayList<>();

        for (VolcanoVent vent : volcano.manager.getVents()) {
            if (vent.getTwoDimensionalDistance(loc) <= range + vent.craterRadius) {
                list.add(vent);

            }
        }
        return list;
    }

    public VolcanoVent getSubVentByVentName(String name) {
        return volcano.subVents.get(name);
    }

    public boolean getSubVentExist(String name) {
        return this.getSubVentByVentName(name) != null;
    }

    public List<VolcanoVent> currentlyStartedVents() {
        Volcano volcano = this.volcano;
        List<VolcanoVent> vents = new ArrayList<>();

        for (VolcanoVent vent : volcano.subVents.values()) {
            if (vent.isStarted()) {
                vents.add(vent);
            }
        }

        if (volcano.mainVent.isStarted()) {
            vents.add(volcano.mainVent);
        }

        return vents;
    }

    public List<VolcanoVent> currentlyLavaFlowingVents() {
        Volcano volcano = this.volcano;
        List<VolcanoVent> vents = new ArrayList<>();

        for (VolcanoVent vent : volcano.subVents.values()) {
            if (vent.isFlowingLava()) {
                vents.add(vent);
            }
        }

        if (volcano.mainVent.isFlowingLava()) {
            vents.add(volcano.mainVent);
        }

        return vents;
    }

    public List<VolcanoVent> currentlyExplodingVents() {
        Volcano volcano = this.volcano;
        List<VolcanoVent> vents = new ArrayList<>();

        for (VolcanoVent vent : volcano.subVents.values()) {
            if (vent.isExploding()) {
                vents.add(vent);
            }
        }

        if (volcano.mainVent.isExploding()) {
            vents.add(volcano.mainVent);
        }

        return vents;
    }

    public VolcanoVent createFissureVent() {
        Volcano volcano = this.volcano;

        int number = -1;
        for (int tmp = 1; tmp < 10; tmp++) {
            if (volcano.subVents.get("fissure"+tmp) == null) {
                number = tmp; 
                break;
            }
        }

        if (number == -1) return null;
        VolcanoVent vent = new VolcanoVent(volcano);
        vent.name = "fissure"+number;

        if (volcano.mainVent.getType() == VolcanoVentType.FISSURE) {
            vent.fissureAngle = volcano.mainVent.fissureAngle;
        } else {
            vent.fissureAngle = Math.random() * 2 * Math.PI;
        }

        volcano.subVents.put(vent.name, vent);
        return vent;
    }
}
