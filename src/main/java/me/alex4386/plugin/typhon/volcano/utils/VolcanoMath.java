package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.volcano.utils.VolcanoCircleOffsetXZ;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class VolcanoMath {
    public static double volcanoPdfVariance = 0.1;

    public static double pdf(double x) {
        return pdf(1, x);
    }

    public static double pdf(double variance, double x) {
        return pdf(0, variance, x);
    }

    public static double pdf(double mean, double variance, double x) {
        double base = 1/Math.sqrt(2*Math.PI*variance);
        double pow = -(Math.pow((x-mean), 2)/(2*variance));
        return Math.pow(Math.E, pow) * base;
    }

    public static double pdfMaxLimiter(double x, double max) {
        return pdfMaxLimiter(1, x, max);
    }

    public static double pdfMaxLimiter(double variance, double x, double max) {
        return pdfMaxLimiter(0, variance, x, max);
    }

    public static double pdfMaxLimiter(double mean, double variance, double x, double max) {
        return (pdf(mean, variance, x) / pdf(mean, variance, mean)) * max;
    }

    public static double volcanoPdf(double x) {
        return pdf(volcanoPdfVariance, x);
    }

    public static double volcanoPdfHeight(double x) {
        return pdfMaxLimiter(volcanoPdfVariance, x, 1);
    }

    public static double magmaPdfHeight(double x) {
        return pdfMaxLimiter(0,1, x, 1);
    }

    public static double getZeroFocusedRandom(double variance) {
        Random random = new Random();

        // range: -infinity -> infinity
        double gaussianRandom = random.nextGaussian();

        // range: 0 -> infinity
        gaussianRandom = Math.abs(gaussianRandom);

        // inside 1, 70%, inside 2, 95%
        double gaussianMappedToOne = pdfMaxLimiter(variance, gaussianRandom, 1);

        return 1.0f - gaussianMappedToOne;
    }

    public static double getZeroFocusedRandom() {
        return getZeroFocusedRandom(1);
    }

    public static double stratoConePdf(double steepness, double x) {
        double mean = -0.6;

        double steepVariance = steepness * 0.1;

        double variance = 0.1 + steepVariance;
        double base = 1/Math.sqrt(2*Math.PI*variance);
        double pow = -(Math.pow((x-mean), 2)/(2*variance));
        return Math.pow(Math.E, pow) * base;
    }

    public static double stratoConePdfHeight(double steepness, double x) {
        return stratoConePdf(steepness, x) / stratoConePdf(steepness, 0);
    }

    public static List<Block> getCircle(Block centerBlock, int radius) {
        return getCircle(centerBlock, radius, -1);
    }

    public static List<Block> getCircle(Block centerBlock, int radius, int hollowRadius) {
        List<Block> circleBlocks = new ArrayList<>();

        double radiusSquared = Math.pow(radius, 2);
        double hollowRadiusSquared = hollowRadius > 0 ? Math.pow(hollowRadius, 2) : -1;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double distanceSquared = Math.pow(x, 2) + Math.pow(z, 2);

                if (distanceSquared <= radiusSquared && distanceSquared > hollowRadiusSquared) {
                    circleBlocks.add(centerBlock.getRelative(x, 0, z));
                }
            }
        }

        return circleBlocks;
    }

    public static List<Block> getCylinder(Block centerBlock, int radius, int height) {
        List<Block> cylinderBlocks = new ArrayList<>();

        for (int i = 0; i < height; i++) {
            Block cylinderCenterBlock = centerBlock.getRelative(0,i,0);
            cylinderBlocks.addAll(getCircle(cylinderCenterBlock, radius));
        }

        return cylinderBlocks;
    }

    public static List<Block> getCylinder(Block centerBlock, int radius, int height, int hollowRadius) {
        List<Block> cylinderBlocks = new ArrayList<>();

        for (int i = 0; i < height; i++) {
            Block cylinderCenterBlock = centerBlock.getRelative(0,i,0);
            cylinderBlocks.addAll(getCircle(cylinderCenterBlock, radius, hollowRadius));
        }

        return cylinderBlocks;
    }

    public static List<Block> getSphere(Block centerBlock, int radius) {
        return getSphere(centerBlock, radius, -1);
    }

    public static List<Block> getSphere(Block centerBlock, int radius, int hollowRadius) {
        List<Block> sphereBlocks = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    double distanceSquared = Math.pow(x, 2) + Math.pow(y, 2) + Math.pow(z, 2);

                    double radiusSquared = 3 * Math.pow(radius - 1, 2);
                    double hollowRadiusSquared = hollowRadius > 0 ? 3 * Math.pow(hollowRadius - 1, 2) : -1;

                    if (distanceSquared <= radiusSquared && distanceSquared > hollowRadiusSquared) {
                        sphereBlocks.add(centerBlock.getRelative(x, y, z));
                    }
                }
            }
        }

        return sphereBlocks;
    }


    public static VolcanoCircleOffsetXZ getCenterFocusedCircleOffset(Block centerBlock, int radius) {
        return getCenterFocusedCircleOffset(centerBlock, radius, 0);
    }

    public static VolcanoCircleOffsetXZ getCenterFocusedCircleOffset(Block centerBlock, int radius, int hollowRadius) {
        double launchRadius = hollowRadius + getZeroFocusedRandom() * (radius - hollowRadius);
        double randomAngle = Math.random() * 2 * Math.PI;

        double x = Math.cos(randomAngle) * launchRadius;
        double z = Math.sin(randomAngle) * launchRadius;

        return new VolcanoCircleOffsetXZ(x, z);
    }
}
