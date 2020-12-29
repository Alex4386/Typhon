package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

public class VolcanoManager {
    public Volcano volcano;

    VolcanoManager(Volcano volcano) {
        this.volcano = volcano;
    }

    public List<VolcanoCrater> getCraters() {
        List<VolcanoCrater> craters = new ArrayList<>();
        craters.add(volcano.mainCrater);
        craters.addAll(volcano.subCraters.values());
        return craters;
    }

    public boolean isInAnyCrater(Block block) {
        return isInAnyCrater(block.getLocation());
    }

    public boolean isInAnyCrater(Location loc) {
        List<VolcanoCrater> craters = this.getCraters();

        for (VolcanoCrater crater : craters) {
            if (crater.isInCrater(loc)) {
                return true;
            }
        }

        return false;
    }

    public ChatColor getChatColor() {
        boolean isErupting = volcano.manager.currentlyStartedCraters().size() > 0;
        return (isErupting ? ChatColor.RED : (
            volcano.status.getScaleFactor() < 0.1 ? ChatColor.GREEN : ChatColor.GOLD
        ));
    }

    public VolcanoCrater getNearestCrater(Block block) {
        return getNearestCrater(block.getLocation());
    }

    public VolcanoCrater getNearestCrater(Location loc) {
        List<VolcanoCrater> craters = this.getCraters();

        VolcanoCrater nearestCrater = null;
        double shortestY = -1;

        for (VolcanoCrater crater : craters) {
            double distance = TyphonUtils.getTwoDimensionalDistance(loc, crater.location);
            if (shortestY < 0 || distance < shortestY) {
                shortestY = distance;
                nearestCrater = crater;
            }
        }

        return nearestCrater;
    }

    public VolcanoCrater getSummitCrater() {
        int y = -1;
        VolcanoCrater summitCrater = null;

        for (VolcanoCrater crater:volcano.subCraters.values()) {
            Block block = crater.getSummitBlock();
            int blockY = block.getY();

            if (blockY >= y) {
                y = blockY;
                summitCrater = crater;
            }
        }

        Block mainCraterSummit = volcano.mainCrater.getSummitBlock();
        if (mainCraterSummit.getY() >= y) {
            summitCrater = volcano.mainCrater;;
            y = mainCraterSummit.getY();
        }

        return summitCrater;
    }

    public Block getSummitBlock() {
        int y = -1;
        VolcanoCrater summitCrater = this.getSummitCrater();

        return summitCrater.getSummitBlock();
    }

    public boolean isInAnyLavaFlowArea(Location loc) {
        List<VolcanoCrater> craters = this.getCraters();

        for (VolcanoCrater crater:craters) {
            if (crater.isInLavaFlow(loc)) {
                return true;
            }
        }

        return false;
    }

    public double getHeatValue(Location loc) {
        double accumulatedHeat = 0.0f;
        for (VolcanoCrater crater : volcano.manager.getCraters()) {
            double distance = crater.getTwoDimensionalDistance(loc);
            double distanceRatio = distance / crater.longestFlowLength;

            double heatValue = VolcanoMath.pdfMaxLimiter(distanceRatio, 1);

            accumulatedHeat = Math.max(heatValue, accumulatedHeat);
        }

        return Math.min(accumulatedHeat, 1.0);
    }

    public VolcanoCrater getSubCraterByCraterName(String name) {
        return volcano.subCraters.get(name);
    }

    public boolean getSubCraterExist(String name) {
        return this.getSubCraterByCraterName(name) != null;
    }

    public List<VolcanoCrater> currentlyStartedCraters() {
        Volcano volcano = this.volcano;
        List<VolcanoCrater> craters = new ArrayList<>();

        for (VolcanoCrater crater : volcano.subCraters.values()) {
            if (crater.isStarted()) {
                craters.add(crater);
            }
        }

        if (volcano.mainCrater.isStarted()) {
            craters.add(volcano.mainCrater);
        }

        return craters;
    }

    public List<VolcanoCrater> currentlyLavaFlowingCraters() {
        Volcano volcano = this.volcano;
        List<VolcanoCrater> craters = new ArrayList<>();

        for (VolcanoCrater crater : volcano.subCraters.values()) {
            if (crater.isFlowingLava()) {
                craters.add(crater);
            }
        }

        if (volcano.mainCrater.isFlowingLava()) {
            craters.add(volcano.mainCrater);
        }

        return craters;
    }

    public List<VolcanoCrater> currentlyEruptingCraters() {
        Volcano volcano = this.volcano;
        List<VolcanoCrater> craters = new ArrayList<>();

        for (VolcanoCrater crater : volcano.subCraters.values()) {
            if (crater.isErupting()) {
                craters.add(crater);
            }
        }

        if (volcano.mainCrater.isErupting()) {
            craters.add(volcano.mainCrater);
        }

        return craters;
    }
}
