package me.alex4386.typhon.simulator.output;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.SectionRaster;
import me.alex4386.typhon.engine.world.UnitRecord;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * Renders the stratigraphy the run laid down, read from the engine's world model: a vertical cross
 * section (west–east through the main vent) coloured by deposit type and shaded by eruption, and
 * stratigraphic column logs at the vent and at a distal point.
 */
public final class SectionRenderer {
    static final int SECTION_WIDTH = 768;
    static final int SECTION_HEIGHT = 360;
    static final int LEGEND_HEIGHT = 44;

    static final Map<DepositType, Color> COLORS = new EnumMap<>(DepositType.class);
    static {
        COLORS.put(DepositType.BASEMENT, new Color(150, 140, 150));
        COLORS.put(DepositType.EDIFICE, new Color(178, 172, 160));
        COLORS.put(DepositType.FILL, new Color(196, 190, 180));
        COLORS.put(DepositType.LAVA, new Color(70, 45, 40));
        COLORS.put(DepositType.HYALOCLASTITE, new Color(60, 90, 95));
        COLORS.put(DepositType.TUBE_ROOF, new Color(110, 60, 45));
        COLORS.put(DepositType.PDC, new Color(214, 160, 90));
        COLORS.put(DepositType.FALL, new Color(232, 214, 120));
        COLORS.put(DepositType.LAHAR, new Color(140, 110, 70));
        COLORS.put(DepositType.INTRUSION, new Color(170, 40, 60));
        COLORS.put(DepositType.CAVITY, new Color(10, 10, 10));
    }
    static final Color SKY = new Color(236, 242, 248);
    static final Color WATER = new Color(80, 130, 210);

    private final Scenario scenario;
    private final WorldModel world;

    public SectionRenderer(Scenario scenario) {
        this.scenario = scenario;
        this.world = scenario.terrain().world();
    }

    /** Writes the section and column logs into {@code dir}, adding file name → caption to {@code maps}. */
    public void writeAll(Path dir, Map<String, String> maps) throws IOException {
        BlockPos vent = scenario.volcano().vents().get(0).position();
        var grid = scenario.initialTerrain();
        int minX = grid.minX();
        int maxX = grid.minX() + grid.size() - 1;
        ImageIO.write(section(minX, maxX, vent.z()), "png", dir.resolve("section-ew.png").toFile());
        maps.put("section-ew.png", "Cross-section west–east through the main vent (z = " + vent.z()
                + "): deposits by type, darker bands = later eruptions; vertical exaggeration varies");
        int distal = distalColumn(vent.x(), vent.z(), maxX);
        ImageIO.write(columns(vent.x(), vent.z(), distal, vent.z()), "png", dir.resolve("section-columns.png").toFile());
        maps.put("section-columns.png", "Stratigraphic columns at the vent (x = " + vent.x() + ") and distal (x = "
                + distal + "): volcanic units, youngest on top");
    }

    /** Farthest column east of the vent that holds volcanic deposits (else halfway to the edge). */
    int distalColumn(int ventX, int z, int maxX) {
        for (int x = maxX; x > ventX; x--) {
            if (!volcanicLayers(x, z).isEmpty()) return x - Math.max(1, (x - ventX) / 6);
        }
        return ventX + (maxX - ventX) / 2;
    }

    // ── cross-section ──

    BufferedImage section(int x0, int x1, int z) {
        int columns = x1 - x0 + 1;
        int nu = Math.min(SECTION_WIDTH, columns);
        double surfaceMin = Double.MAX_VALUE;
        double surfaceMax = -Double.MAX_VALUE;
        double deepest = Double.MAX_VALUE; // deepest volcanic layer bottom
        for (int x = x0; x <= x1; x++) {
            double s = world.surfaceZ(x, z);
            if (Double.isNaN(s)) continue;
            surfaceMin = Math.min(surfaceMin, s);
            surfaceMax = Math.max(surfaceMax, s);
            for (LayerView layer : volcanicLayers(x, z)) deepest = Math.min(deepest, layer.bottom());
        }
        if (surfaceMin == Double.MAX_VALUE) return blank();
        double relief = Math.max(10, surfaceMax - surfaceMin);
        double zMax = surfaceMax + 0.08 * relief;
        double zMin = Math.min(surfaceMin - 0.25 * relief, deepest == Double.MAX_VALUE ? surfaceMin : deepest - 0.1 * relief);
        double dx = (double) columns / nu;
        SectionRaster raster = world.section(new double[] {x0, z + 0.5, x1 + 1, z + 0.5}, zMin, zMax, nu, SECTION_HEIGHT);

        BufferedImage img = new BufferedImage(nu, SECTION_HEIGHT + LEGEND_HEIGHT, BufferedImage.TYPE_INT_RGB);
        int maxEruption = 1;
        for (UnitRecord u : world.units().all()) maxEruption = Math.max(maxEruption, u.eruptionId());
        for (int iu = 0; iu < nu; iu++) {
            int x = x0 + (int) Math.floor((iu + 0.5) * dx);
            double water = world.waterZ(x, z);
            for (int iz = 0; iz < SECTION_HEIGHT; iz++) {
                int index = raster.index(iu, iz);
                int unit = raster.unit()[index];
                double elevation = zMax - (iz + 0.5) * (zMax - zMin) / SECTION_HEIGHT;
                Color c;
                if (unit < 0) {
                    c = !Double.isNaN(water) && elevation <= water ? WATER : SKY;
                } else if (raster.material()[index] == MaterialTable.VOID.id()) {
                    c = COLORS.get(DepositType.CAVITY);
                } else {
                    c = unitColor(world.unit(unit), maxEruption);
                }
                img.setRGB(iu, iz, c.getRGB());
            }
        }
        Graphics2D g = graphics(img);
        // surface line
        g.setColor(new Color(30, 30, 30));
        for (int iu = 1; iu < nu; iu++) {
            g.drawLine(iu - 1, row(raster.surfaceZ()[iu - 1], zMin, zMax), iu, row(raster.surfaceZ()[iu], zMin, zMax));
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString(String.format("%.0f m", zMax), 4, 12);
        g.drawString(String.format("%.0f m", zMin), 4, SECTION_HEIGHT - 4);
        double widthM = columns * world.spec().metersPerColumn();
        g.drawString(String.format("W  ·  %.0f m  ·  E", widthM), nu / 2 - 50, SECTION_HEIGHT - 4);
        legend(g, nu, SECTION_HEIGHT);
        g.dispose();
        return img;
    }

    /** Deposit-type colour, darkened for later eruptions so successive eruptions show as bands. */
    static Color unitColor(UnitRecord u, int maxEruption) {
        Color base = COLORS.getOrDefault(u.type(), Color.MAGENTA);
        if (u.volcanoId() == null || u.eruptionId() <= 1 || maxEruption <= 1) return base;
        double f = 1 - 0.45 * (u.eruptionId() - 1) / (double) (maxEruption - 1);
        return new Color((int) (base.getRed() * f), (int) (base.getGreen() * f), (int) (base.getBlue() * f));
    }

    private static int row(double elevation, double zMin, double zMax) {
        return (int) Math.round((zMax - elevation) / (zMax - zMin) * SECTION_HEIGHT);
    }

    // ── column logs ──

    BufferedImage columns(int x1, int z1, int x2, int z2) {
        int w = 520;
        int h = 420;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(img);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        drawColumn(g, 20, 30, h - 70, "vent (" + x1 + ", " + z1 + ")", volcanicLayers(x1, z1));
        drawColumn(g, 270, 30, h - 70, "distal (" + x2 + ", " + z2 + ")", volcanicLayers(x2, z2));
        legend(g, w, h - 40);
        g.dispose();
        return img;
    }

    private void drawColumn(Graphics2D g, int left, int top, int height, String title, List<LayerView> layers) {
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString(title, left, top - 10);
        if (layers.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString("no volcanic deposits", left, top + 14);
            return;
        }
        double total = 0;
        for (LayerView l : layers) total += l.thickness();
        // log-ish scaling so millimetre ash and metre lava are both visible
        double[] px = new double[layers.size()];
        double sum = 0;
        for (int i = 0; i < layers.size(); i++) {
            px[i] = 4 + Math.log1p(layers.get(i).thickness() * 100);
            sum += px[i];
        }
        double scale = height / sum;
        int maxEruption = 1;
        for (UnitRecord u : world.units().all()) maxEruption = Math.max(maxEruption, u.eruptionId());
        double y = top + height;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        for (int i = 0; i < layers.size(); i++) { // bottom first
            LayerView layer = layers.get(i);
            UnitRecord u = world.unit(layer.unit());
            double hpx = px[i] * scale;
            int y0 = (int) Math.round(y - hpx);
            g.setColor(layer.material() == MaterialTable.VOID.id() ? COLORS.get(DepositType.CAVITY) : unitColor(u, maxEruption));
            g.fillRect(left, y0, 70, Math.max(1, (int) Math.round(y) - y0));
            g.setColor(Color.DARK_GRAY);
            g.drawRect(left, y0, 70, Math.max(1, (int) Math.round(y) - y0));
            if (hpx >= 10) {
                g.setColor(Color.BLACK);
                g.drawString(String.format("%s #%d %s  %s", u.type(), u.eruptionId(),
                        MaterialTable.get(layer.material()).name(), thickness(layer.thickness())),
                        left + 78, (int) Math.round(y - hpx / 2 + 4));
            }
            y -= hpx;
        }
        g.setColor(Color.BLACK);
        g.drawString("total " + thickness(total) + " (log-scaled heights)", left, top + height + 14);
    }

    static String thickness(double m) {
        if (m >= 1) return String.format("%.1f m", m);
        if (m >= 0.01) return String.format("%.0f cm", m * 100);
        return String.format("%.1f mm", m * 1000);
    }

    /** Volcanic layers of a column (attributed to a volcano, or cavities), bottom to top. */
    List<LayerView> volcanicLayers(int x, int z) {
        List<LayerView> out = new ArrayList<>();
        int n = world.layerCount(x, z);
        for (int k = 0; k < n; k++) {
            LayerView layer = world.layer(x, z, k);
            UnitRecord u = world.unit(layer.unit());
            boolean volcanic = u.volcanoId() != null || (u.type() != DepositType.BASEMENT
                    && u.type() != DepositType.EDIFICE && u.type() != DepositType.FILL);
            if (volcanic) out.add(layer);
        }
        return out;
    }

    // ── shared ──

    private void legend(Graphics2D g, int width, int top) {
        Map<String, Color> entries = new LinkedHashMap<>();
        for (DepositType t : List.of(DepositType.LAVA, DepositType.TUBE_ROOF, DepositType.CAVITY, DepositType.HYALOCLASTITE,
                DepositType.FALL, DepositType.PDC, DepositType.LAHAR, DepositType.INTRUSION, DepositType.EDIFICE,
                DepositType.BASEMENT)) {
            entries.put(t.name().toLowerCase(), COLORS.get(t));
        }
        g.setColor(Color.WHITE);
        g.fillRect(0, top, width, LEGEND_HEIGHT);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        int x = 6;
        int y = top + 8;
        for (Map.Entry<String, Color> e : entries.entrySet()) {
            int textWidth = g.getFontMetrics().stringWidth(e.getKey());
            if (x + 14 + textWidth > width - 4) {
                x = 6;
                y += 16;
            }
            g.setColor(e.getValue());
            g.fillRect(x, y, 10, 10);
            g.setColor(Color.DARK_GRAY);
            g.drawRect(x, y, 10, 10);
            g.setColor(Color.BLACK);
            g.drawString(e.getKey(), x + 14, y + 9);
            x += 14 + textWidth + 12;
        }
    }

    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1));
        return g;
    }

    private static BufferedImage blank() {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(SKY);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        return img;
    }
}
