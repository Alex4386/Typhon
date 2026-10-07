package me.alex4386.typhon.simulator.diagnostics;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLanded;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.Scenario;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic (not part of the default suite): azimuthal distribution of proximal tephra around the
 * vent of a Strombolian preset. Run with {@code ./gradlew :simulator:slowTest --tests '*ScoriaDistributionProbe*'}
 * with env PROBE_PRESET / PROBE_HOURS.
 */
@Tag("slow")
class ScoriaDistributionProbe {
    static final int SECTORS = 16;

    @Test
    void report() {
        String preset = System.getenv().getOrDefault("PROBE_PRESET", "stromboli");
        double hours = Double.parseDouble(System.getenv().getOrDefault("PROBE_HOURS", "1"));
        Scenario s = Presets.get(preset).build(1);
        TephraSubsystem tephra = s.volcano().tephra();
        Point3 vent = s.volcano().vents().get(0).position();
        double l = s.world().spec().metersPerColumn();
        int vx = vent.columnX(l), vz = vent.columnZ(l);
        List<Point3> landings = new ArrayList<>();
        boolean first = true;
        for (double t = 0; t < hours * 3600; t += 60) {
            for (EngineFrame f : s.engine().runFor(60)) {
                for (EngineEvent e : f.events()) if (e instanceof BombLanded b) landings.add(b.position());
            }
            if (first) {
                s.runAfterFirstTick();
                first = false;
            }
        }
        System.out.printf("PROBE preset=%s hours=%.1f vent=%s wind=%.1f m/s toward %.0f deg (rad %.2f)%n", preset, hours,
                vent, tephra.wind().baseSpeed(), Math.toDegrees(tephra.wind().baseDirectionRad()),
                tephra.wind().baseDirectionRad());
        System.out.println("PROBE ash budget " + tephra.massBudget());

        // Ash deposit by sector and ring (m from the vent; sum of thickness over columns).
        double[][] ring = {{0, 500}, {500, 1500}, {1500, 4000}, {4000, 15000}};
        for (double[] r : ring) {
            double[] sector = new double[SECTORS];
            double sx = 0, sz = 0, total = 0;
            int R = (int) Math.ceil(r[1] / l);
            for (int dz = -R; dz <= R; dz += 1) {
                for (int dx = -R; dx <= R; dx += 1) {
                    double d = Math.hypot(dx, dz) * l;
                    if (d < r[0] || d >= r[1] || d == 0) continue;
                    double h = tephra.depositThickness(vx + dx, vz + dz);
                    if (h <= 0) continue;
                    double a = Math.atan2(dz, dx);
                    sector[sectorOf(a)] += h;
                    sx += h * dx * l / d;
                    sz += h * dz * l / d;
                    total += h;
                }
            }
            System.out.printf("PROBE ash ring %5.0f-%5.0f m: total=%.4g resultant=%.2f toward %.0f deg sectors=%s%n",
                    r[0], r[1], total, total > 0 ? Math.hypot(sx, sz) / total : 0, Math.toDegrees(Math.atan2(sz, sx)),
                    fmt(sector, total));
        }
        double[] sector = new double[SECTORS];
        double sx = 0, sz = 0;
        for (Point3 p : landings) {
            double dx = p.x() - vent.x(), dz = p.z() - vent.z();
            double d = Math.hypot(dx, dz);
            if (d == 0) continue;
            sector[sectorOf(Math.atan2(dz, dx))]++;
            sx += dx / d;
            sz += dz / d;
        }
        System.out.printf("PROBE bombs landed=%d resultant=%.2f toward %.0f deg sectors=%s%n", landings.size(),
                landings.isEmpty() ? 0 : Math.hypot(sx, sz) / landings.size(), Math.toDegrees(Math.atan2(sz, sx)),
                fmt(sector, landings.size()));
    }

    static int sectorOf(double a) {
        int i = (int) Math.floor((a + Math.PI) / (2 * Math.PI) * SECTORS);
        return Math.min(SECTORS - 1, Math.max(0, i));
    }

    static String fmt(double[] v, double total) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) b.append(i == 0 ? "" : " ").append(total > 0 ? Math.round(100 * v[i] / total) : 0);
        return b.append("]%").toString();
    }
}
