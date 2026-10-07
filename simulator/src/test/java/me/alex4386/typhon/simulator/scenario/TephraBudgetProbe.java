package me.alex4386.typhon.simulator.scenario;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.alex4386.typhon.engine.assembly.VentPartition;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.config.ChamberPlacement;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.worlds.World;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Diagnostic: where the erupted mass of a submarine/Surtseyan eruption goes. Builds the island flow's
 * empty ocean (depth {@code -Dbudget.depth}, default 130 m) with the same placed chamber, runs
 * {@code -Dbudget.hours} hours of eruption (default 3) and prints, per interval and cumulatively:
 * erupted magma, the partition's paths (lava, fall-back ballistics, cock's-tail jets, wet fallout,
 * lofted column, collapsing column), the ash grid's budget, the bulk volume added to the ground by
 * distance band and submerged/subaerial, and a radial thickness profile. Run with
 * {@code ./gradlew :simulator:tephraBudget}.
 */
@Tag("budget")
class TephraBudgetProbe {
    private static final double DEPTH = Double.parseDouble(System.getProperty("budget.depth", "130"));
    private static final double HOURS = Double.parseDouble(System.getProperty("budget.hours", "3"));
    private static final double[] BANDS = {100, 500, 1200};

    @Test
    void budget(@TempDir Path worlds) {
        Path dir = worlds.resolve("sea");
        WorldTemplates.Template d = IslandFlowTest.ocean();
        WorldTemplates.Template ocean = new WorldTemplates.Template(d.kind(), d.coreExtentM(), d.metersPerColumn(), DEPTH,
                d.elevationM(), d.slope(), d.seaLevelZ(), d.roughnessM());
        WorldTemplates.writeEmpty(ocean, "sea", 1, dir);
        Scenario s = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
        s.engine().step();
        WorldModel wm0 = s.terrain().world();
        double l = wm0.spec().metersPerColumn();
        double floor = wm0.surfaceZ(0, 0);
        World world = s.session();
        VolcanoDefinition def = ChamberPlacement.definition("surtur", IslandFlowTest.chamber(), floor, l);
        world.addVolcano(def);
        int half = (int) Math.round(BANDS[BANDS.length - 1] / l);
        double[][] base = snapshot(s.terrain().world(), half);

        // wait for the first eruption (quiet recharge is cheap: long steps)
        while (!s.volcano().chamber().erupting() && s.engine().time() < 3e7) s.engine().step();
        double start = s.engine().time();
        System.out.printf(Locale.ROOT, "BUDGET depth %.0f m: first eruption after %.1f h; column %.0f m%n", DEPTH, start / 3600, l);

        Acc acc = new Acc();
        double lastPrint = start;
        double erupted0 = s.volcano().chamber().eruptedVolume();
        TephraSubsystem.MassBudget grid0 = s.volcano().tephra().massBudget();
        while (s.engine().time() - start < HOURS * 3600) {
            VolcanoSystem v = s.volcano();
            MagmaChamber ch = v.chamber();
            double t0 = s.engine().time();
            s.engine().step();
            double dt = s.engine().time() - t0;
            VentPartition.Result p = v.coupler().partition();
            if (p != null && ch.erupting() && p.magmaMassFlux() > 0) {
                double scale = ch.eruptionRate() * ExplosivePhase.DRE_DENSITY / p.magmaMassFlux() * dt;
                acc.add(p, scale);
            }
            if (s.engine().time() - lastPrint >= 1800 || s.engine().time() - start >= HOURS * 3600) {
                lastPrint = s.engine().time();
                report(s, acc, base, floor, l, half, erupted0, grid0, start);
            }
        }
    }

    private static void report(Scenario s, Acc acc, double[][] base, double floor, double l, int half, double erupted0,
            TephraSubsystem.MassBudget grid0, double start) {
        VolcanoSystem v = s.volcano();
        MagmaChamber ch = v.chamber();
        double eruptedKg = (ch.eruptedVolume() - erupted0) * ExplosivePhase.DRE_DENSITY;
        TephraSubsystem.MassBudget g = v.tephra().massBudget();
        double ms = s.volcano().tephra().config().massScale;
        WorldModel wm = s.terrain().world();
        double[] band = new double[BANDS.length + 1];
        double sub = 0;
        double aerial = 0;
        double top = Double.NEGATIVE_INFINITY;
        double[] profileSum = new double[BANDS.length * 0 + 13];
        int[] profileN = new int[profileSum.length];
        for (int z = -half; z <= half; z++) {
            for (int x = -half; x <= half; x++) {
                if (!wm.isKnown(x, z)) continue;
                double before = base[z + half][x + half];
                double now = wm.surfaceZ(x, z);
                double dz = now - before;
                top = Math.max(top, now);
                double r = Math.hypot(x, z) * l;
                double vol = dz * l * l;
                int b = 0;
                while (b < BANDS.length && r > BANDS[b]) b++;
                band[b] += vol;
                // split the added thickness into the part below and above sea level
                double lo = Math.min(before, now);
                double hi = Math.max(before, now);
                double below = Math.max(0, Math.min(hi, 0) - lo);
                double above = Math.max(0, hi - Math.max(lo, 0));
                sub += Math.signum(dz) * below * l * l;
                aerial += Math.signum(dz) * above * l * l;
                int ring = (int) Math.min(profileSum.length - 1, r / 50);
                profileSum[ring] += dz;
                profileN[ring]++;
            }
        }
        double built = band[0] + band[1] + band[2] + band[3];
        // the ash grid's own thickness record over the window (applied to the ground or still pending)
        double gridVolume = 0;
        for (int z = -half; z <= half; z++) {
            for (int x = -half; x <= half; x++) {
                if (wm.isKnown(x, z)) gridVolume += v.tephra().depositThickness(x, z) * l * l;
            }
        }
        var gm = v.geomorphology() == null ? null : v.geomorphology().snapshot();
        System.out.printf(Locale.ROOT, "  ash-grid thickness volume in window %.3g m³ (at its bulk density %.0f kg/m³); geomorph: recycled into vent %.3g m³, excavated %.3g m³, failed %.3g m³%n",
                gridVolume, v.tephra().config().depositBulkDensity, gm == null ? 0 : gm.recycledM3(), gm == null ? 0 : gm.excavatedM3(),
                gm == null ? 0 : gm.failedM3());
        System.out.printf(Locale.ROOT,
                "BUDGET t=%.2f h  erupted %.3g kg (%.3g m³ DRE)  top %.1f m%n"
                        + "  paths kg: lava %.3g | fall-back ballistics %.3g | jets %.3g | wet fallout %.3g | column lofted %.3g | column collapsing %.3g | steam %.3g%n"
                        + "  ash grid (real kg): emitted %.3g deposited %.3g airborne %.3g exported %.3g discarded %.3g%n"
                        + "  bulk added m³: <100 m %.3g | 100-500 %.3g | 500-1200 %.3g | >1200 (in window) %.3g | total %.3g (submerged %.3g, subaerial %.3g)%n"
                        + "  bulk/DRE ratio %.2f (expected ~%.2f for bulk density 1500 kg/m³)%n",
                (s.engine().time() - start) / 3600, eruptedKg, eruptedKg / ExplosivePhase.DRE_DENSITY, top,
                acc.lava, acc.ballistic, acc.jet, acc.wet, acc.lofted, acc.collapsing, acc.steam,
                (g.emitted() - grid0.emitted()) / ms, (g.deposited() - grid0.deposited()) / ms, g.airborne() / ms,
                (g.exported() - grid0.exported()) / ms, (g.discarded() - grid0.discarded()) / ms,
                band[0], band[1], band[2], band[3], built, sub, aerial,
                built / Math.max(1e-9, eruptedKg / ExplosivePhase.DRE_DENSITY), ExplosivePhase.DRE_DENSITY / 1500.0);
        StringBuilder sb = new StringBuilder("  radial mean added thickness (m) per 50 m ring:");
        for (int i = 0; i < profileSum.length; i++) {
            sb.append(String.format(Locale.ROOT, " %d:%.2f", i * 50, profileN[i] > 0 ? profileSum[i] / profileN[i] : 0));
        }
        System.out.println(sb);
        if (acc.samples > 0) {
            System.out.printf(Locale.ROOT, "  mean partition: water/magma R %.3f, wet share %.2f, jet speed %.0f m/s, ballistic speed %.0f m/s, fountain %.0f m%n",
                    acc.ratio / acc.samples, acc.wetShare / acc.samples, acc.jetSpeed / Math.max(1, acc.jetSamples),
                    acc.ballisticSpeed / acc.samples, acc.fountain / acc.samples);
        }
    }

    private static double[][] snapshot(WorldModel wm, int half) {
        double[][] out = new double[2 * half + 1][2 * half + 1];
        for (int z = -half; z <= half; z++) {
            for (int x = -half; x <= half; x++) out[z + half][x + half] = wm.isKnown(x, z) ? wm.surfaceZ(x, z) : 0;
        }
        return out;
    }

    private static final class Acc {
        double lava, ballistic, jet, wet, lofted, collapsing, steam;
        double ratio, wetShare, jetSpeed, ballisticSpeed, fountain;
        int samples, jetSamples;
        final List<String> notes = new ArrayList<>();

        void add(VentPartition.Result p, double scale) {
            lava += p.lavaMassFlux() * scale;
            ballistic += p.ballisticMassFlux() * scale;
            jet += p.jetMassFlux() * scale;
            wet += p.wetFalloutMassFlux() * scale;
            lofted += p.columnMassFlux() * (1 - p.collapseFraction()) * scale;
            collapsing += p.columnMassFlux() * p.collapseFraction() * scale;
            steam += p.steamMassFlux() * scale;
            ratio += p.waterMagmaRatio();
            wetShare += p.magmaMassFlux() > 0 ? p.waterFragmentedMassFlux() / p.magmaMassFlux() : 0;
            if (p.jetMassFlux() > 0) {
                jetSpeed += p.jetSpeed();
                jetSamples++;
            }
            ballisticSpeed += p.ballisticSpeed();
            fountain += p.fountainHeightM();
            samples++;
        }
    }
}
