package me.alex4386.typhon.simulator.output;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.geothermal.HydrothermalFeature;
import me.alex4386.typhon.engine.geothermal.PlacedFeature;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.simulator.scenario.Scenario;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.world.VoxelWorld;

/**
 * Renders top-down maps of a finished run (north up, +X right) with {@code javax.imageio} only.
 * Large areas are down-sampled so images stay under {@link #MAX_PIXELS} pixels across.
 */
public final class MapRenderer {
    public static final int MAX_PIXELS = 768;

    private final Scenario scenario;
    private final ColumnGrid grid;
    private final int step;
    private final int width;
    private final double[][] top;      // final top solid y
    private final double[][] initial;  // initial ground y

    public MapRenderer(Scenario scenario) {
        this.scenario = scenario;
        this.grid = scenario.initialTerrain();
        this.step = Math.max(1, (grid.size() + MAX_PIXELS - 1) / MAX_PIXELS);
        this.width = grid.size() / step;
        this.top = new double[width][width];
        this.initial = new double[width][width];
        VoxelWorld world = scenario.world();
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                int x = x(i);
                int z = z(j);
                top[j][i] = world.topSolidY(x, z);
                initial[j][i] = grid.ground(x, z);
            }
        }
    }

    private int x(int i) { return grid.minX() + i * step; }
    private int z(int j) { return grid.minZ() + j * step; }

    /** Writes every map into {@code dir}; returns file name → caption. */
    public Map<String, String> writeAll(Path dir) throws IOException {
        Map<String, String> maps = new LinkedHashMap<>();
        write(dir, "map-elevation.png", elevation(), maps, "Final surface: elevation, water (blue), lava (orange)");
        write(dir, "map-change.png", change(), maps, "Elevation change: red = built up, blue = removed");
        write(dir, "map-lava.png", lava(), maps, "Lava: molten (yellow-orange) and new rock thickness (brown)");
        write(dir, "map-ash.png", ash(), maps, "Tephra deposit thickness (log scale, 1 mm to 10 m)");
        if (scenario.volcano().geothermal() != null) {
            write(dir, "map-geothermal.png", geothermal(), maps,
                    "Shallow subsurface temperature and hydrothermal features");
        }
        new SectionRenderer(scenario).writeAll(dir, maps);
        return maps;
    }

    private void write(Path dir, String name, BufferedImage image, Map<String, String> maps, String caption)
            throws IOException {
        ImageIO.write(image, "png", dir.resolve(name).toFile());
        maps.put(name, caption);
    }

    // ── maps ──

    BufferedImage elevation() {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] row : top) for (double v : row) { min = Math.min(min, v); max = Math.max(max, v); }
        BufferedImage img = image();
        VoxelWorld world = scenario.world();
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                double shade = hillshade(top, i, j);
                double t = (top[j][i] - min) / Math.max(1, max - min);
                Color c = ramp(t, TERRAIN);
                BlockId topId = world.topBlock(x(i), z(j)).id();
                if (topId.equals(VoxelWorld.LAVA)) c = new Color(255, 120, 20);
                else if (topId.equals(VoxelWorld.WATER)) c = new Color(40, 90, 200);
                img.setRGB(i, j, shadeColor(c, shade).getRGB());
            }
        }
        return img;
    }

    BufferedImage change() {
        BufferedImage img = image();
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                double d = top[j][i] - initial[j][i];
                double shade = hillshade(top, i, j);
                Color base = new Color(200, 200, 200);
                Color c = d > 0 ? mix(base, new Color(200, 30, 20), Math.min(1, d / 8))
                        : d < 0 ? mix(base, new Color(20, 60, 200), Math.min(1, -d / 8)) : base;
                img.setRGB(i, j, shadeColor(c, shade).getRGB());
            }
        }
        return img;
    }

    BufferedImage lava() {
        BufferedImage img = image();
        var lava = scenario.lava();
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                int x = x(i), z = z(j);
                double shade = hillshade(top, i, j);
                double molten = lava.thickness(x, z);
                double raised = top[j][i] - initial[j][i];
                Color c;
                if (molten > 0.01) {
                    c = ramp(Math.min(1, molten / 3), MOLTEN);
                } else if (raised > 0) {
                    c = mix(new Color(150, 120, 100), new Color(60, 25, 15), Math.min(1, raised / 6));
                } else {
                    c = new Color(205, 205, 205);
                }
                img.setRGB(i, j, shadeColor(c, shade).getRGB());
            }
        }
        return img;
    }

    BufferedImage ash() {
        BufferedImage img = image();
        TephraSubsystem tephra = scenario.volcano().tephra();
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                double thickness = tephra.depositThickness(x(i), z(j));
                double shade = hillshade(top, i, j);
                Color c = new Color(225, 230, 215);
                if (thickness > 1e-3) {
                    double t = (Math.log10(thickness) + 3) / 4; // 1 mm .. 10 m
                    c = ramp(Math.max(0, Math.min(1, t)), ASH);
                }
                img.setRGB(i, j, shadeColor(c, shade).getRGB());
            }
        }
        return img;
    }

    BufferedImage geothermal() {
        BufferedImage img = image();
        Geothermal geo = scenario.volcano().geothermal();
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                int x = x(i), z = z(j);
                double shade = hillshade(top, i, j);
                double t = geo.grid().containsBlock(x, z) ? geo.temperatureAt(x, z) : Double.NaN;
                TerrainColumn col = grid.column(x, z);
                Color c;
                if (Double.isNaN(t)) {
                    c = new Color(210, 210, 210);
                } else {
                    c = ramp(Math.max(0, Math.min(1, (t - 15) / 285)), HEAT);
                }
                if (col.submerged()) c = mix(c, new Color(40, 90, 200), 0.35);
                img.setRGB(i, j, shadeColor(c, 0.5 + 0.5 * shade).getRGB());
            }
        }
        // Pervasive features (alteration, sinter, cinnabar) tint their column; discrete vents get a dot on top.
        for (PlacedFeature f : geo.featuresByColumn().values()) {
            if (!AREA_FEATURES.contains(f.kind())) continue;
            int i = (f.x() - grid.minX()) / step;
            int j = (f.z() - grid.minZ()) / step;
            if (i >= 0 && j >= 0 && i < width && j < width) img.setRGB(i, j, FEATURE_COLORS.get(f.kind()).getRGB());
        }
        for (PlacedFeature f : geo.featuresByColumn().values()) {
            if (AREA_FEATURES.contains(f.kind())) continue;
            int i = (f.x() - grid.minX()) / step;
            int j = (f.z() - grid.minZ()) / step;
            dot(img, i, j, FEATURE_COLORS.getOrDefault(f.kind(), Color.WHITE));
        }
        return img;
    }

    static final java.util.Set<HydrothermalFeature> AREA_FEATURES = java.util.EnumSet.of(
            HydrothermalFeature.ACID_ALTERATION, HydrothermalFeature.SINTER, HydrothermalFeature.CINNABAR);

    static final Map<HydrothermalFeature, Color> FEATURE_COLORS = new LinkedHashMap<>();

    static {
        FEATURE_COLORS.put(HydrothermalFeature.FUMAROLE, new Color(255, 255, 255));
        FEATURE_COLORS.put(HydrothermalFeature.GEYSER, new Color(0, 230, 255));
        FEATURE_COLORS.put(HydrothermalFeature.HOT_SPRING, new Color(30, 120, 255));
        FEATURE_COLORS.put(HydrothermalFeature.SULFUR_SPRING, new Color(180, 255, 60));
        FEATURE_COLORS.put(HydrothermalFeature.MUD_POT, new Color(120, 80, 40));
        FEATURE_COLORS.put(HydrothermalFeature.SUBMARINE_VENT, new Color(0, 0, 120));
        FEATURE_COLORS.put(HydrothermalFeature.SULFUR_DEPOSIT, new Color(255, 230, 0));
        FEATURE_COLORS.put(HydrothermalFeature.ACID_ALTERATION, new Color(230, 140, 90));
        FEATURE_COLORS.put(HydrothermalFeature.SINTER, new Color(240, 240, 220));
        FEATURE_COLORS.put(HydrothermalFeature.CINNABAR, new Color(200, 0, 40));
    }

    // ── helpers ──

    private BufferedImage image() {
        return new BufferedImage(width, width, BufferedImage.TYPE_INT_RGB);
    }

    private void dot(BufferedImage img, int ci, int cj, Color color) {
        int r = Math.max(1, 2 / step);
        for (int dj = -r; dj <= r; dj++) {
            for (int di = -r; di <= r; di++) {
                int i = ci + di, j = cj + dj;
                if (i < 0 || j < 0 || i >= width || j >= width) continue;
                boolean edge = Math.abs(di) == r || Math.abs(dj) == r;
                img.setRGB(i, j, edge ? Color.BLACK.getRGB() : color.getRGB());
            }
        }
    }

    /** Lambertian hillshade in [0, 1], light from the north-west at 45°. */
    private double hillshade(double[][] h, int i, int j) {
        double dzdx = (h[j][Math.min(width - 1, i + 1)] - h[j][Math.max(0, i - 1)]) / (2.0 * step);
        double dzdy = (h[Math.min(width - 1, j + 1)][i] - h[Math.max(0, j - 1)][i]) / (2.0 * step);
        double nx = -dzdx, ny = -dzdy, nz = 1;
        double norm = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double lx = -0.5, ly = -0.5, lz = Math.sqrt(0.5);
        return Math.max(0, (nx * lx + ny * ly + nz * lz) / norm);
    }

    private static final Color[] TERRAIN = {
        new Color(70, 120, 60), new Color(150, 160, 90), new Color(150, 120, 90), new Color(110, 100, 100),
        new Color(235, 235, 235)};
    private static final Color[] MOLTEN = {new Color(255, 230, 80), new Color(255, 120, 0), new Color(180, 20, 0)};
    private static final Color[] ASH = {new Color(200, 200, 190), new Color(120, 120, 120), new Color(40, 40, 45)};
    private static final Color[] HEAT = {
        new Color(30, 40, 120), new Color(40, 160, 160), new Color(240, 220, 60), new Color(240, 90, 20),
        new Color(160, 0, 0)};

    static Color ramp(double t, Color[] stops) {
        double p = Math.max(0, Math.min(1, t)) * (stops.length - 1);
        int k = Math.min(stops.length - 2, (int) p);
        return mix(stops[k], stops[k + 1], p - k);
    }

    static Color mix(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static Color shadeColor(Color c, double shade) {
        double f = 0.45 + 0.75 * shade;
        return new Color(
                (int) Math.min(255, c.getRed() * f),
                (int) Math.min(255, c.getGreen() * f),
                (int) Math.min(255, c.getBlue() * f));
    }
}
