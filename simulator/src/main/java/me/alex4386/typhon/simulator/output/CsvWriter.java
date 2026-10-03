package me.alex4386.typhon.simulator.output;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.alex4386.typhon.simulator.run.Sample;

/** Writes the sampled time series as CSV (columns: tick, time, alert, style, then numeric values). */
public final class CsvWriter {
    private CsvWriter() {}

    public static void write(Path file, List<Sample> samples) throws IOException {
        try (Writer out = Files.newBufferedWriter(file)) {
            write(out, samples);
        }
    }

    public static String toString(List<Sample> samples) {
        StringWriter out = new StringWriter();
        try {
            write(out, samples);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toString();
    }

    static void write(Writer out, List<Sample> samples) throws IOException {
        if (samples.isEmpty()) return;
        out.write("tick,time_s,alert_level_name,style_name");
        for (String column : samples.get(0).values().keySet()) out.write("," + column);
        out.write('\n');
        for (Sample s : samples) {
            out.write(s.tick() + "," + format(s.timeSeconds()) + "," + s.alertLevel() + "," + s.style());
            for (Map.Entry<String, Double> e : s.values().entrySet()) out.write("," + format(e.getValue()));
            out.write('\n');
        }
    }

    static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.6g", v);
    }
}
