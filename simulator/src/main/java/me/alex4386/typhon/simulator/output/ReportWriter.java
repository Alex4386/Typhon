package me.alex4386.typhon.simulator.output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.alex4386.typhon.engine.alert.AlertLevel;
import me.alex4386.typhon.simulator.run.ReferenceComparison;
import me.alex4386.typhon.simulator.run.RunSummary;
import me.alex4386.typhon.simulator.scenario.RealSetting;
import me.alex4386.typhon.simulator.scenario.ReferenceValue;
import me.alex4386.typhon.simulator.run.Sample;
import me.alex4386.typhon.simulator.run.Simulation;
import me.alex4386.typhon.simulator.scenario.Preset;

/** Writes a self-contained HTML report: summary, timeline, inline SVG charts and embedded maps. */
public final class ReportWriter {
    private ReportWriter() {}

    /** A chart: one or more series drawn on a shared y axis. */
    record Chart(String title, String unit, boolean log, List<Series> series) {}

    record Series(String column, String label, String color) {}

    static final List<Chart> CHARTS = List.of(
            new Chart("Chamber overpressure", "MPa", false, List.of(
                    new Series("overpressure_mpa", "overpressure", "#c0392b"))),
            new Chart("Eruption rate (real, DRE)", "m³/s", false, List.of(
                    new Series("eruption_rate_m3s", "eruption rate", "#e67e22"),
                    new Series("supply_rate_m3s", "supply rate", "#7f8c8d"))),
            new Chart("Seismicity", "RSAM / events per min", true, List.of(
                    new Series("rsam", "RSAM", "#2c3e50"),
                    new Series("vt_per_min", "VT/min", "#8e44ad"),
                    new Series("lp_per_min", "LP/min", "#16a085"))),
            new Chart("Alert level", "level", false, List.of(
                    new Series("alert_level", "alert level", "#d35400"))),
            new Chart("Magma state", "wt% / log10 Pa·s", false, List.of(
                    new Series("silica_wt", "SiO₂ wt% / 10", "#34495e"),
                    new Series("water_wt", "H₂O wt%", "#2980b9"),
                    new Series("viscosity_log10", "log10 viscosity", "#27ae60"))),
            new Chart("Lava", "m³", false, List.of(
                    new Series("lava_volume_m3", "molten", "#e74c3c"),
                    new Series("lava_solidified_m3", "solidified", "#6e2c00"),
                    new Series("lava_emitted_m3", "emitted", "#f39c12"))),
            new Chart("Eruption column", "m above the vent", false, List.of(
                    new Series("plume_height_m", "plume height", "#7f8c8d"))),
            new Chart("Tephra (model mass)", "kg", true, List.of(
                    new Series("ash_emitted_kg", "emitted", "#95a5a6"),
                    new Series("ash_deposited_kg", "deposited", "#2c3e50"))),
            new Chart("Geothermal", "count / activity", false, List.of(
                    new Series("geothermal_activity", "activity", "#c0392b"),
                    new Series("features_fumarole", "fumaroles", "#bdc3c7"),
                    new Series("features_geyser", "geysers", "#00bcd4"),
                    new Series("features_hot_spring", "hot springs", "#2980b9"),
                    new Series("features_sulfur_deposit", "sulfur deposits", "#f1c40f"))));

    public static void write(Path file, Preset preset, Simulation.Result result, Map<String, String> maps,
            Path mapDir) throws IOException {
        Files.writeString(file, render(preset, result, maps, mapDir));
    }

    static String render(Preset preset, Simulation.Result result, Map<String, String> maps, Path mapDir)
            throws IOException {
        RunSummary s = result.summary();
        List<Sample> samples = result.samples();
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Typhon run: ").append(esc(preset.name())).append("</title><style>")
                .append("body{font:14px/1.5 system-ui,sans-serif;margin:0 auto;max-width:1000px;padding:16px;color:#222;background:#fafafa}")
                .append("h1{margin-bottom:0}table{border-collapse:collapse;margin:8px 0}td,th{padding:3px 10px;border-bottom:1px solid #ddd;text-align:left}")
                .append("th{background:#eee}.chart{background:#fff;border:1px solid #ddd;margin:10px 0;padding:6px}")
                .append(".maps{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:10px}")
                .append(".maps figure{margin:0;background:#fff;border:1px solid #ddd;padding:6px}.maps img{width:100%;image-rendering:pixelated}")
                .append("small,.muted{color:#666}code{background:#eee;padding:1px 4px}</style></head><body>");

        h.append("<h1>").append(esc(preset.title())).append("</h1>");
        h.append("<p class=\"muted\">preset <code>").append(esc(preset.name())).append("</code> · seed ")
                .append(result.scenario().seed()).append(" · ").append(fmt(result.simulatedSeconds() / 3600)).append(" h in ")
                .append(fmt(result.wallSeconds())).append(" s (").append(fmt(result.stepsPerSecond())).append(" steps/s, ")
                .append(fmt(result.speedup())).append("× real time, base step ")
                .append(fmt(result.scenario().engine().baseStepMicros() / 1000.0)).append(" ms)</p>");
        h.append("<p>").append(esc(preset.description())).append("</p>");

        h.append("<h2>Summary</h2><table>");
        row(h, "Eruptions started", Integer.toString(s.eruptions));
        row(h, "First eruption", Double.isNaN(s.firstEruptionSeconds) ? "none" : time(s.firstEruptionSeconds));
        row(h, "Peak eruption rate (real DRE)", fmt(s.peakEruptionRate) + " m³/s"
                + (Double.isNaN(s.peakEruptionRateSeconds) ? "" : " at " + time(s.peakEruptionRateSeconds)));
        Sample last = samples.get(samples.size() - 1);
        row(h, "Erupted volume, all eruptions (real DRE)", fmt(s.totalEruptedVolume()) + " m³");
        row(h, "Final alert level / style", esc(last.alertLevel()) + " / " + esc(last.style()));
        row(h, "Lava emitted / solidified", fmt(last.get("lava_emitted_m3")) + " / "
                + fmt(last.get("lava_solidified_m3")) + " m³");
        row(h, "Longest lava flow", fmt(s.maxFlowLengthM) + " m");
        row(h, "Highest plume top", Double.isNaN(s.maxPlumeTopZ) ? "none" : fmt(s.maxPlumeTopZ) + " m a.s.l."
                + " (MER " + fmt(s.maxPlumeMassRate) + " kg/s)");
        row(h, "Ballistic bombs launched / landed", s.bombsLaunched + " / " + s.bombsLanded
                + (s.bombsLanded > 0 ? " (farthest " + fmt(s.maxBombDistance) + " m from the origin)" : ""));
        row(h, "Volcanic lightning", Long.toString(s.lightning));
        row(h, "Earthquakes", s.seismicCounts.toString() + (s.maxMagnitude > -10 ? ", max M" + fmt(s.maxMagnitude) : ""));
        row(h, "Hydrothermal features formed", s.featuresFormed.isEmpty() ? "none" : s.featuresFormed.toString());
        row(h, "Lava ocean-entry reports", Long.toString(s.lavaWaterEntries));
        row(h, "World-model column edits", fmt(last.get("world_edits")));
        row(h, "Column width", fmt(result.scenario().world().spec().metersPerColumn()) + " m");
        h.append("</table>");

        if (!s.eruptionRecords.isEmpty()) {
            h.append("<h2>Eruptions</h2><table><tr><th>volcano</th><th>start</th><th>end</th><th>duration</th>")
                    .append("<th>volume (DRE)</th><th>styles</th><th>trigger</th></tr>");
            for (RunSummary.Eruption e : s.eruptionRecords) {
                double end = e.ongoing() ? result.simulatedSeconds() : e.endSeconds();
                h.append("<tr><td>").append(esc(e.volcanoId())).append("</td><td>").append(time(e.startSeconds()))
                        .append("</td><td>").append(e.ongoing() ? "ongoing" : time(e.endSeconds())).append("</td><td>")
                        .append(time(Math.max(0, end - e.startSeconds()))).append("</td><td>").append(fmt(e.volumeM3()))
                        .append(" m³</td><td>").append(esc(String.join(" → ", e.styles()))).append("</td><td>")
                        .append(esc(e.startCause())).append("</td></tr>");
            }
            h.append("</table>");
        }

        List<ReferenceComparison.Row> comparison = ReferenceComparison.compare(preset, result);
        if (!comparison.isEmpty()) {
            h.append("<h2>Reference vs model</h2><table><tr><th>quantity</th><th>reference</th><th>model</th>")
                    .append("<th></th><th>source</th></tr>");
            for (ReferenceComparison.Row row : comparison) {
                ReferenceValue ref = row.reference();
                h.append("<tr><td>").append(esc(ref.quantity())).append("</td><td>").append(esc(ref.referenceText()))
                        .append("</td><td>").append(esc(row.modelText())).append("</td><td>").append(verdict(row.verdict()))
                        .append(ref.informative() ? " <small class=\"muted\">(context only: " + esc(ref.note()) + ")</small>" : "")
                        .append("</td><td><small>").append(esc(ref.source())).append("</small></td></tr>");
            }
            h.append("</table><p class=\"muted\">A sanity check of magnitudes and behaviour over this run's horizon,"
                    + " not a calibration target.</p>");
        }

        h.append("<h2>Reference values</h2><ul>");
        for (String ref : preset.references()) h.append("<li>").append(esc(ref)).append("</li>");
        h.append("</ul>");
        RealSetting real = preset.realSetting();
        if (real != null && !real.dem().available()) {
            h.append(String.format(Locale.ROOT, "<p class=\"muted\">Real-scale domain %.1f km at %s m per column. %s</p>",
                    real.domainMeters() / 1000, fmt(real.metersPerColumn()), esc(real.dem().notes())));
        } else if (real != null) {
            RealSetting.DemSource dem = real.dem();
            h.append(String.format(Locale.ROOT, "<p class=\"muted\">Real-scale domain %.1f km at %s m per column,"
                            + " centred on %.4f, %.4f. Real DEM: <code>%s</code> (Copernicus GLO-30) or SRTM <code>%s</code>."
                            + " %s</p>", real.domainMeters() / 1000, fmt(real.metersPerColumn()), dem.lat(), dem.lon(),
                    esc(dem.copernicusTile()), esc(dem.srtmTile()), esc(dem.notes())));
        }

        h.append("<h2>Timeline</h2><table><tr><th>time</th><th>event</th></tr>");
        int shown = 0;
        for (RunSummary.Milestone m : s.milestones) {
            if (shown++ >= 80) {
                h.append("<tr><td></td><td class=\"muted\">… ").append(s.milestones.size() - 80).append(" more</td></tr>");
                break;
            }
            h.append("<tr><td>").append(time(m.timeSeconds())).append("</td><td>").append(esc(m.description())).append("</td></tr>");
        }
        h.append("</table>");

        h.append("<h2>Time series</h2>");
        for (Chart chart : CHARTS) {
            String svg = svg(chart, samples);
            if (svg != null) h.append("<div class=\"chart\">").append(svg).append("</div>");
        }

        h.append("<h2>Maps</h2><div class=\"maps\">");
        for (Map.Entry<String, String> map : maps.entrySet()) {
            byte[] png = Files.readAllBytes(mapDir.resolve(map.getKey()));
            h.append("<figure><img alt=\"").append(esc(map.getValue())).append("\" src=\"data:image/png;base64,")
                    .append(Base64.getEncoder().encodeToString(png)).append("\"><figcaption><small>")
                    .append(esc(map.getValue())).append(" — <a href=\"").append(map.getKey()).append("\">")
                    .append(map.getKey()).append("</a></small></figcaption></figure>");
        }
        h.append("</div>");

        h.append("<h2>Event counts</h2><table><tr><th>type</th><th>count</th></tr>");
        for (Map.Entry<String, Long> e : s.eventCounts.entrySet()) {
            h.append("<tr><td>").append(esc(e.getKey())).append("</td><td>").append(e.getValue()).append("</td></tr>");
        }
        h.append("</table><p class=\"muted\">Generated by the Typhon headless simulator. Real scale, SI units.</p></body></html>\n");
        return h.toString();
    }

    /** Inline SVG line chart; {@code null} if none of its series has data. */
    static String svg(Chart chart, List<Sample> samples) {
        int w = 940, ht = 200, left = 60, right = 10, top = 24, bottom = 28;
        double t0 = samples.get(0).timeSeconds();
        double t1 = Math.max(t0 + 1, samples.get(samples.size() - 1).timeSeconds());
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        boolean any = false;
        for (Series series : chart.series()) {
            for (Sample s : samples) {
                double v = value(chart, series, s);
                if (Double.isNaN(v) || Double.isInfinite(v)) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
                any = true;
            }
        }
        if (!any) return null;
        if (chart.title().equals("Alert level")) {
            min = 0;
            max = AlertLevel.values().length - 1;
        }
        if (max - min < 1e-9) { max = min + 1; }
        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(ht)
                .append("\" width=\"100%\" role=\"img\" aria-label=\"").append(esc(chart.title())).append("\">");
        svg.append("<text x=\"").append(left).append("\" y=\"15\" font-weight=\"bold\" font-size=\"13\">")
                .append(esc(chart.title())).append(" <tspan font-weight=\"normal\" fill=\"#666\">(")
                .append(esc(chart.unit())).append(chart.log() ? ", log10" : "").append(")</tspan></text>");
        double plotW = w - left - right, plotH = ht - top - bottom;
        svg.append("<rect x=\"").append(left).append("\" y=\"").append(top).append("\" width=\"").append(plotW)
                .append("\" height=\"").append(plotH).append("\" fill=\"none\" stroke=\"#ccc\"/>");
        for (int k = 0; k <= 4; k++) {
            double v = min + (max - min) * k / 4;
            double y = top + plotH - plotH * k / 4;
            String label = chart.title().equals("Alert level")
                    ? (k == 0 ? "EXTINCT" : k == 4 ? "ERUPT" : fmt(v)) : (chart.log() ? "1e" + fmt(v) : fmt(v));
            svg.append("<text x=\"").append(left - 4).append("\" y=\"").append(f(y + 4))
                    .append("\" font-size=\"10\" text-anchor=\"end\" fill=\"#666\">").append(esc(label)).append("</text>");
            svg.append("<line x1=\"").append(left).append("\" x2=\"").append(w - right).append("\" y1=\"").append(f(y))
                    .append("\" y2=\"").append(f(y)).append("\" stroke=\"#eee\"/>");
        }
        for (int k = 0; k <= 6; k++) {
            double t = t0 + (t1 - t0) * k / 6;
            double x = left + plotW * k / 6;
            svg.append("<text x=\"").append(f(x)).append("\" y=\"").append(ht - 8)
                    .append("\" font-size=\"10\" text-anchor=\"middle\" fill=\"#666\">").append(time(t)).append("</text>");
        }
        int legendX = left + 8;
        for (Series series : chart.series()) {
            StringBuilder points = new StringBuilder();
            int stride = Math.max(1, samples.size() / 1500);
            for (int idx = 0; idx < samples.size(); idx += stride) {
                Sample s = samples.get(idx);
                double v = value(chart, series, s);
                if (Double.isNaN(v) || Double.isInfinite(v)) continue;
                double x = left + plotW * (s.timeSeconds() - t0) / (t1 - t0);
                double y = top + plotH - plotH * (v - min) / (max - min);
                points.append(f(x)).append(',').append(f(y)).append(' ');
            }
            if (points.isEmpty()) continue;
            svg.append("<polyline fill=\"none\" stroke-width=\"1.5\" stroke=\"").append(series.color())
                    .append("\" points=\"").append(points).append("\"/>");
            svg.append("<text x=\"").append(legendX).append("\" y=\"").append(top + 12).append("\" font-size=\"11\" fill=\"")
                    .append(series.color()).append("\">■ ").append(esc(series.label())).append("</text>");
            legendX += 12 + 7 * series.label().length();
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static double value(Chart chart, Series series, Sample s) {
        double v = s.get(series.column());
        if (series.column().equals("silica_wt")) v /= 10;
        if (chart.log()) return v > 0 ? Math.log10(v) : Double.NaN;
        return v;
    }

    private static String verdict(ReferenceValue.Verdict v) {
        return switch (v) {
            case WITHIN, MATCH -> "<span style=\"color:#1e7e34\">✓ within</span>";
            case BELOW -> "<span style=\"color:#b35c00\">▼ below</span>";
            case ABOVE -> "<span style=\"color:#b35c00\">▲ above</span>";
            case MISMATCH -> "<span style=\"color:#b35c00\">✗ differs</span>";
            case NOT_OBSERVED -> "<span class=\"muted\">not observed</span>";
        };
    }

    private static void row(StringBuilder h, String key, String value) {
        h.append("<tr><th>").append(esc(key)).append("</th><td>").append(value).append("</td></tr>");
    }

    static String time(double seconds) {
        long s = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static String fmt(double v) {
        if (Double.isNaN(v)) return "–";
        if (v == Math.rint(v) && Math.abs(v) < 1e7) return Long.toString((long) v);
        if (Math.abs(v) >= 1e5 || Math.abs(v) < 1e-2) return String.format(Locale.ROOT, "%.3g", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
