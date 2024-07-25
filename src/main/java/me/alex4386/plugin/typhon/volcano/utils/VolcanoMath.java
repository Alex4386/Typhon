package me.alex4386.plugin.typhon.volcano.utils;

import com.flowpowered.math.vector.Vector3d;
import me.alex4386.plugin.typhon.TyphonUtils;
import org.bukkit.Material;
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
        double base = 1 / Math.sqrt(2 * Math.PI * variance);
        double pow = -(Math.pow((x - mean), 2) / (2 * variance));
        return Math.pow(Math.E, pow) * base;
    }

    public static List<Vector3d> getSmoothedOut(Block centerBlock, int radius) {
        List<Block> smoothedOutTargetBlocks = getCircle(centerBlock, radius);
        List<Block> highestBlocks = new ArrayList<>();

        long ySum = 0;
        for (Block block:smoothedOutTargetBlocks) {
            Block highest = TyphonUtils.getHighestRocklikes(block);
            highestBlocks.add(highest);

            int highestY = highest.getY();
            ySum += highestY;
        }

        double diffDivisor = 2;

        List<Vector3d> smoothedOut = new ArrayList<>();
        double yAverage = ySum / (double) smoothedOutTargetBlocks.size();
        for (Block block:highestBlocks) {
            double diff = block.getY() - yAverage;

            double modAmount = (diff / diffDivisor);
            modAmount = Math.max(-radius, Math.min(radius, modAmount));

            double newY = block.getY() - modAmount;
            smoothedOut.add(new Vector3d(block.getX(), newY, block.getZ()));
        }

        return smoothedOut;
    }

    public static void smoothOutRadius(Block centerBlock, int radius, Material fillMaterial) {
        List<Vector3d> smoothedOut = getSmoothedOut(centerBlock, radius);

        for (Vector3d vector:smoothedOut) {
            Block block = centerBlock.getWorld().getBlockAt((int) vector.getX(), (int) vector.getY(), (int) vector.getZ());
            Block highestBlock = TyphonUtils.getHighestRocklikes(block);

            if (highestBlock.getY() < block.getY()) {
                for (int y = highestBlock.getY(); y <= block.getY(); y++) {
                    Block currentBlock = block.getWorld().getBlockAt(block.getX(), y, block.getZ());
                    currentBlock.setType(fillMaterial);
                }
            } else if (highestBlock.getY() > block.getY()) {
                for (int y = block.getY(); y > highestBlock.getY(); y--) {
                    Block currentBlock = block.getWorld().getBlockAt(block.getX(), y, block.getZ());
                    currentBlock.setType(Material.AIR);
                }
            }
        }
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
        return pdfMaxLimiter(0, 1, x, 1);
    }

    public static double getZeroFocusedRandom(double variance) {
        Random random = new Random();

        /*
            // range: -infinity -> infinity
            double gaussianRandom = random.nextGaussian();

            // range: 0 -> infinity
            gaussianRandom = Math.abs(gaussianRandom);

            // inside 1, 70%, inside 2, 95%
            double gaussianMappedToOne = pdfMaxLimiter(variance, gaussianRandom, 1);

            return 1.0f - gaussianMappedToOne;
        */

        return Math.pow(random.nextDouble(), 2 + (Math.random() * variance));
    }

    public static double getZeroFocusedRandom() {
        return getZeroFocusedRandom(1);
    }

    public static double stratoConePdf(double steepness, double x) {
        double mean = -0.6;

        double steepVariance = steepness * 0.1;

        double variance = 0.1 + steepVariance;
        double base = 1 / Math.sqrt(2 * Math.PI * variance);
        double pow = -(Math.pow((x - mean), 2) / (2 * variance));
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

    public static List<Block> getLine(Block centerBlock, double angle, int length) {
        List<Block> lineBlocks = new ArrayList<>();

        for (double i = 0; i <= (length / 2); i++) {
            double x = Math.sin(angle) * i;
            double z = Math.cos(angle) * i;

            Block negativeBlock = centerBlock.getRelative((int) -x, 0, (int) -z);
            if (!lineBlocks.contains(negativeBlock)) {
                lineBlocks.add(negativeBlock);
            }

            Block block = centerBlock.getRelative((int) x, 0, (int) z);
            if (!lineBlocks.contains(block)) {
                lineBlocks.add(block);
            }
        }

        return lineBlocks;
    }

    public static List<Block> getCylinder(Block centerBlock, int radius, int height) {
        List<Block> cylinderBlocks = new ArrayList<>();

        for (int i = 0; i < height; i++) {
            Block cylinderCenterBlock = centerBlock.getRelative(0, i, 0);
            cylinderBlocks.addAll(getCircle(cylinderCenterBlock, radius));
        }

        return cylinderBlocks;
    }

    public static List<Block> getCylinder(
            Block centerBlock, int radius, int height, int hollowRadius) {
        List<Block> cylinderBlocks = new ArrayList<>();

        for (int i = 0; i < height; i++) {
            Block cylinderCenterBlock = centerBlock.getRelative(0, i, 0);
            cylinderBlocks.addAll(getCircle(cylinderCenterBlock, radius, hollowRadius));
        }

        return cylinderBlocks;
    }

    public static List<Block> getCube(Block centerBlock, int radius) {
        return getCube(centerBlock, radius, -1);
    }

    public static List<Block> getCube(Block centerBlock, int radius, int hollowRadius) {
        List<Block> sphereBlocks = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) > hollowRadius && Math.abs(y) > hollowRadius && Math.abs(z) > hollowRadius) {
                        sphereBlocks.add(centerBlock.getRelative(x, y, z));
                    }
                }
            }
        }

        return sphereBlocks;
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
                    double hollowRadiusSquared =
                            hollowRadius > 0 ? 3 * Math.pow(hollowRadius - 1, 2) : -1;

                    if (distanceSquared <= radiusSquared && distanceSquared > hollowRadiusSquared) {
                        sphereBlocks.add(centerBlock.getRelative(x, y, z));
                    }
                }
            }
        }

        return sphereBlocks;
    }

    public static VolcanoCircleOffsetXZ getCenterFocusedCircleOffset(
            Block centerBlock, int radius) {
        return getCenterFocusedCircleOffset(centerBlock, radius, 0);
    }

    public static VolcanoCircleOffsetXZ getCenterFocusedCircleOffset(
            Block centerBlock, int radius, int hollowRadius) {
        double launchRadius = hollowRadius + getZeroFocusedRandom() * (radius - hollowRadius);
        double randomAngle = Math.random() * 2 * Math.PI;

        double x = Math.cos(randomAngle) * launchRadius;
        double z = Math.sin(randomAngle) * launchRadius;

        return new VolcanoCircleOffsetXZ(x, z);
    }

    // generate white noise
    public static double[][] generateWhiteNoise(int width, int height, Random random) {
        double[][] noise = new double[width][height];

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j ++) {
                noise[i][j] = random.nextDouble() % 1;
            }
        }

        return noise;
    }

    private static double perlinNoiseFade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double perlinNoiseLinearInterpolate(double w, double x, double y) {
        return x + w * (y - x);
    }

    private static double perlinNoiseGradientVector(int directionHash, double x, double y, double z) {
        // 16 directions: 0-15 - utilize hash to optimize
        int direction = directionHash & 15;

        double u = direction < 8 ? x : y;
        double v = direction < 4 ? y : direction == 12 || direction == 14 ? x : z;
        return ((direction & 1) == 0 ? u : -u) + ((direction & 2) == 0 ? v : -v);
    }

    private static int[] generatePerlinNoisePermutation() {
        // Initialize permutation array inside the function
        int[] permutation = new int[512];
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }

        Random random = new Random();
        for (int i = 255; i > 0; i--) {
            int index = random.nextInt(i + 1);
            int temp = p[index];
            p[index] = p[i];
            p[i] = temp;
        }

        for (int i = 0; i < 512; i++) {
            permutation[i] = p[i & 255];
        }

        return permutation;
    }

    private static double generatePerlinNoiseAt(double x, double y, double z) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        int Z = (int) Math.floor(z) & 255;

        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);

        double u = perlinNoiseFade(x);
        double v = perlinNoiseFade(y);
        double w = perlinNoiseFade(z);

        int[] permutation = generatePerlinNoisePermutation();

        int A = permutation[X] + Y;
        int AA = permutation[A] + Z;
        int AB = permutation[A + 1] + Z;
        int B = permutation[X + 1] + Y;
        int BA = permutation[B] + Z;
        int BB = permutation[B + 1] + Z;

        return perlinNoiseLinearInterpolate(w, perlinNoiseLinearInterpolate(v, perlinNoiseLinearInterpolate(u, perlinNoiseGradientVector(permutation[AA], x, y, z),
                                perlinNoiseGradientVector(permutation[BA], x - 1, y, z)),
                        perlinNoiseLinearInterpolate(u, perlinNoiseGradientVector(permutation[AB], x, y - 1, z),
                                perlinNoiseGradientVector(permutation[BB], x - 1, y - 1, z))),
                perlinNoiseLinearInterpolate(v, perlinNoiseLinearInterpolate(u, perlinNoiseGradientVector(permutation[AA + 1], x, y, z - 1),
                                perlinNoiseGradientVector(permutation[BA + 1], x - 1, y, z - 1)),
                        perlinNoiseLinearInterpolate(u, perlinNoiseGradientVector(permutation[AB + 1], x, y - 1, z - 1),
                                perlinNoiseGradientVector(permutation[BB + 1], x - 1, y - 1, z - 1))));
    }

    // generate
    public static double[][] generatePerlinNoise(int width, int height, double cellSize) {
        double[][] noiseArray = new double[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                double nx = x / cellSize;
                double ny = y / cellSize;

                // Generate raw Perlin noise value
                double rawNoise = generatePerlinNoiseAt(nx, ny, 0);

                // Adjust the raw noise with attenuation and scale it to the range of 0.0 to 1.0
                noiseArray[x][y] = (rawNoise + 1) / 2;
                if (noiseArray[x][y] > 1) noiseArray[x][y] = 1;
                if (noiseArray[x][y] < 0) noiseArray[x][y] = 0;
            }
        }

        return noiseArray;
    }
}
