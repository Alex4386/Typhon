package me.alex4386.typhon.engine.magma.conduit;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.magma.ConduitConfig;
import me.alex4386.typhon.engine.magma.MeltDensity;
import me.alex4386.typhon.engine.magma.MeltViscosity;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution.Fragmentation;

/**
 * Steady, one-dimensional conduit flow from the chamber to the vent (homogeneous gas–melt–crystal
 * mixture with permeable outgassing, decompression crystallisation and fragmentation). The full
 * derivation and references are in {@code docs/eruption-dynamics.md}; in short:
 *
 * <pre>
 *   dp/dz = −(ρ g + F) / (1 + G² ∂v/∂p)       v = n R_w T / p + (1 − n)/ρ_m,  u = G v
 *   F = 8 η u / r²  (coherent, Poiseuille)    F = f ρ u² / (4 r)  (fragmented, turbulent)
 * </pre>
 *
 * For a trial mass flux per area {@code G} the profile is integrated upward; the flux is admissible
 * if the flow reaches the vent at ambient pressure, or chokes exactly at the vent above it. Larger
 * fluxes choke or run out of pressure below the vent. Scanning {@code G} finds every admissible
 * flux: a conduit can sustain both a slow outgassed (effusive) and a fast gas-rich (fragmented)
 * steady flow at the same chamber pressure (Melnik &amp; Sparks 1999; Degruyter et al. 2012), and the
 * chamber picks the branch from the conduit's history, not from a chosen style.
 *
 * <p>Pure and deterministic: the same inputs always give the same solution.
 */
public final class ConduitModel {
    static final double GRAVITY = 9.81;
    static final double R_WATER = 461.5;
    static final double R_CO2 = 188.9;
    /** CO₂ solubility in silicate melt, wt% per MPa (Henry's law, ~0.5 ppm/bar; Dixon 1997). */
    public static final double CO2_SOLUBILITY = 5e-4;
    static final double WATER_DENSITY = 1000;
    /** H₂O solubility coefficient, wt% per √MPa (Wilson &amp; Head 1981). */
    public static final double SOLUBILITY = 0.411;
    /**
     * Melt viscosity (Pa·s) below which a fragmenting foam tears inertially into coarse clots (fluid basaltic
     * fountains) rather than breaking brittlely (Namiki &amp; Manga 2008). The boundary is a convention within the
     * gap between basaltic (≤ 10³) and andesitic (≥ 10⁵ Pa·s) melts, not a measured threshold.
     */
    static final double INERTIAL_VISCOSITY = 1e4;

    // Solver: the mass-flux search range (kg/m²/s), its scan density and bisection depth (numerical).
    static final double G_MIN = 1e-4;
    static final double G_MAX = 3e6;
    static final int SCAN_PER_DECADE = 4;
    static final int BISECTIONS = 24;

    /** Which admissible steady flow to follow. */
    public enum Branch {
        /** The slowest flow: an open conduit or a fresh dike lets magma start rising gently. */
        SLOWEST,
        /** The fastest flow: a sealed conduit failing suddenly is decompressed rapidly. */
        FASTEST
    }

    private ConduitModel() {}

    /** Dissolved water (wt%) at pressure {@code p} (Pa) for a melt that started with {@code c0}. */
    public static double dissolvedWaterWt(double c0, double p) {
        return Math.min(c0, SOLUBILITY * Math.sqrt(Math.max(0, p) / 1e6));
    }

    // ── Public solving API ──

    /** Every admissible mass flux per area (kg/m²/s), slowest first. */
    public static List<Double> admissibleFluxes(ConduitInput in, ConduitConfig c) {
        Flow flow = new Flow(in, c);
        List<Double> roots = new ArrayList<>();
        int points = (int) Math.ceil(Math.log10(G_MAX / G_MIN) * SCAN_PER_DECADE) + 1;
        double prevG = G_MIN;
        boolean prevOk = flow.integrate(prevG, null);
        for (int i = 1; i < points; i++) {
            double g = G_MIN * Math.pow(10, (double) i / SCAN_PER_DECADE);
            boolean ok = flow.integrate(g, null);
            if (prevOk && !ok) roots.add(flow.refine(prevG, g));
            prevG = g;
            prevOk = ok;
        }
        if (prevOk) roots.add(prevG);
        return roots;
    }

    /** The steady flow on the requested branch, or {@code null} if the conduit cannot flow. */
    public static ConduitSolution solve(ConduitInput in, ConduitConfig c, Branch branch) {
        List<Double> roots = admissibleFluxes(in, c);
        if (roots.isEmpty()) return null;
        double g = branch == Branch.FASTEST ? roots.get(roots.size() - 1) : roots.get(0);
        return new Flow(in, c).solution(g);
    }

    /**
     * The steady flow closest to a previous mass flux (kg/s): an eruption stays on its branch while
     * it exists and only jumps when the branch disappears (hysteresis). {@code null} if the conduit
     * cannot flow.
     */
    public static ConduitSolution solveNear(ConduitInput in, ConduitConfig c, double previousMassFlux) {
        Flow flow = new Flow(in, c);
        double area = Math.PI * in.radiusM() * in.radiusM();
        double target = previousMassFlux / area;
        if (target > G_MIN) {
            double lo = Math.max(G_MIN, target / 2);
            double hi = Math.min(G_MAX, target * 2);
            if (flow.integrate(lo, null) && !flow.integrate(hi, null)) {
                return flow.solution(flow.refine(lo, hi));
            }
        }
        List<Double> roots = admissibleFluxes(in, c);
        if (roots.isEmpty()) return null;
        double best = roots.get(0);
        double bestDistance = Double.POSITIVE_INFINITY;
        double logTarget = Math.log(Math.max(target, G_MIN));
        for (double g : roots) {
            double d = Math.abs(Math.log(g) - logTarget);
            if (d < bestDistance) {
                bestDistance = d;
                best = g;
            }
        }
        return flow.solution(best);
    }

    /** Whether a mass flux per area (kg/m²/s) reaches the vent as a steady flow. */
    public static boolean admissible(ConduitInput in, ConduitConfig c, double massFluxPerArea) {
        return new Flow(in, c).integrate(massFluxPerArea, null);
    }

    /** The flow at a given mass flux per area, which must be admissible (for tests and diagnostics). */
    public static ConduitSolution evaluate(ConduitInput in, ConduitConfig c, double massFluxPerArea) {
        return new Flow(in, c).solution(massFluxPerArea);
    }

    // ── Integration ──

    /** Profile summary captured at the vent of an admissible trial. */
    private static final class Exit {
        double p;
        double u;
        boolean choked;
        double n;
        double alpha;
        double gasConstant;
        Fragmentation fragmentation = Fragmentation.NONE;
        double fragmentationDepth = Double.NaN;
        double meltViscosityLog10;
        double crystals;
        double dissolved;
        double outgassed;
        double totalGas;
    }

    /** Crystal and volatile state of the rising magma, as mass fractions of the flow. */
    private static final class State {
        /** Microlites grown in the conduit (volume fraction). */
        double phi;
        /** Water exsolved so far, including gas already free in the chamber. */
        double ew;
        /** CO₂ exsolved so far. */
        double ec;
        /** Water vapour still carried by the mixture. */
        double nw;
        /** CO₂ still carried by the mixture. */
        double nc;
        /** Gas lost permeably through the bubble network. */
        double lost;

        double carried() {
            return nw + nc;
        }

        void set(State o) {
            phi = o.phi;
            ew = o.ew;
            ec = o.ec;
            nw = o.nw;
            nc = o.nc;
            lost = o.lost;
        }
    }

    private static final class Flow {
        final ConduitInput in;
        final ConduitConfig c;
        final double tK;
        final double r;
        final double length;
        final double c0;
        final double co2;
        final double phiChamber;
        final double melt;
        final double maxMicrolites;
        final double gasWater;
        final double gasCo2;
        final double ambient;
        final double chamberViscosity;
        /** Density of the melt entering the conduit (its composition and dissolved water). */
        final double inletMelt;
        final double[] z;

        // per-trial constants
        double gFlux;
        double segregated;

        // scratch outputs of fields() / dpdz()
        double v;
        double n;
        double alpha;
        double u;
        double gasConstant;
        double dissolved;
        /** Melt density at the current dissolved water (kg/m³). */
        double meltDensity;
        double meltViscosity;
        double pureMeltViscosity;
        double denominator;
        boolean choke;

        Flow(ConduitInput in, ConduitConfig c) {
            this.in = in;
            this.c = c;
            this.tK = in.temperatureC() + 273.15;
            this.r = in.radiusM();
            this.length = in.lengthM();
            this.c0 = Math.max(0, in.dissolvedWaterWt());
            this.co2 = Math.max(0, in.co2Wt());
            this.phiChamber = Math.max(0, Math.min(c.maxCrystalFraction(), in.crystalFraction()));
            this.melt = 1 - phiChamber;
            this.maxMicrolites = Math.max(0, c.maxCrystalFraction() - phiChamber);
            this.gasWater = Math.max(0, in.exsolvedGasMassFraction());
            this.ambient = in.ventAmbientPressurePa();
            this.gasCo2 = co2Exsolved(in.chamberPressurePa());
            this.chamberViscosity = Math.pow(10, MeltViscosity.log10(in.silicaWt(), c0, in.temperatureC(), phiChamber));
            this.inletMelt = MeltDensity.meltKgPerM3(in.silicaWt(), c0);
            int steps = c.gridSteps();
            this.z = new double[steps + 1];
            for (int i = 0; i <= steps; i++) {
                double s = 1 - (double) i / steps;
                z[i] = length * (1 - s * s); // refined towards the vent, where gradients steepen
            }
        }

        /** Water exsolved at equilibrium at pressure {@code p}, as a mass fraction of the flow. */
        double waterExsolved(double p) {
            return gasWater + melt * (c0 - dissolvedWaterWt(c0, p)) / 100;
        }

        /** CO₂ exsolved at equilibrium at pressure {@code p} (Henry's law). */
        double co2Exsolved(double p) {
            return melt * Math.max(0, co2 - CO2_SOLUBILITY * p / 1e6) / 100;
        }

        double dissolvedWater(State s) {
            return Math.max(0, c0 - (s.ew - gasWater) * 100 / melt);
        }

        /**
         * Share of the exsolving gas that segregates from the melt as coalescing slugs: Taylor-bubble
         * rise speed against the melt's ascent speed, times a coalescence efficiency that fades in
         * viscous melt.
         */
        double segregation(double g) {
            double ascent = g / inletMelt;
            double d = 2 * r;
            double inertial = 0.345 * Math.sqrt(GRAVITY * d);
            double viscous = 0.01 * inletMelt * GRAVITY * d * d / chamberViscosity;
            double taylor = Math.min(inertial, viscous);
            double coalescence = 1 / (1 + chamberViscosity / c.coalescenceViscosity());
            return coalescence * taylor / (taylor + ascent);
        }

        /** Mixture properties at pressure {@code p} and state {@code s} (sets the scratch fields). */
        void fields(double p, State s) {
            n = Math.max(0, s.carried());
            gasConstant = n > 0 ? (s.nw * R_WATER + s.nc * R_CO2) / n : R_WATER;
            double gasVolume = n * gasConstant * tK / p;
            // the melt densifies as its water exsolves
            dissolved = dissolvedWater(s);
            meltDensity = MeltDensity.meltKgPerM3(in.silicaWt(), dissolved);
            v = gasVolume + (1 - n) / meltDensity;
            alpha = gasVolume / v;
            u = gFlux * v;
            double phiTotal = Math.min(c.maxCrystalFraction(), phiChamber + s.phi);
            pureMeltViscosity = Math.pow(10, MeltViscosity.meltLog10(in.silicaWt(), dissolved, in.temperatureC()));
            meltViscosity = Math.pow(10, MeltViscosity.log10(in.silicaWt(), dissolved, in.temperatureC(), phiTotal));
        }

        /**
         * Pressure gradient for state {@code s} when the carried gas changes at {@code gasRate} per
         * metre (exsolution minus outgassing). The singular point is the frozen sound speed: the gas
         * fraction cannot follow a pressure wave faster than it exsolves. Sets {@link #choke} instead
         * of returning a value when the flow is at or past it.
         */
        double dpdz(double p, State s, double gasRate, boolean fragmented) {
            fields(p, s);
            double friction;
            if (!fragmented) {
                double a = Math.min(alpha, 0.99);
                double ca = meltViscosity * c.bubbleRadiusM() * (u / r) / c.surfaceTension();
                double eta0 = 1 / (1 - a);
                double etaInf = Math.pow(1 - a, 5.0 / 3.0);
                double kc = 1.2 * ca;
                double relative = etaInf + (eta0 - etaInf) / (1 + kc * kc);
                // Viscous wall shear, until the magma fails along the conduit margin and slides as a
                // plug on a frictional shear zone (Iverson et al. 2006): the wall stress saturates at
                // a cohesion plus friction on the wall's normal stress, the magma pressure.
                double slip = c.wallSlipStressPa() + c.wallFrictionCoefficient() * p;
                friction = Math.min(8 * meltViscosity * relative * u / (r * r), 2 * slip / r);
            } else {
                friction = c.turbulentFrictionFactor() * u * u / (4 * r * v);
            }
            double dvdp = -n * gasConstant * tK / (p * p);
            double dvdn = gasConstant * tK / p - 1 / meltDensity;
            denominator = 1 + gFlux * gFlux * dvdp;
            choke = denominator <= 1e-6;
            if (choke) return Double.NaN;
            return -(GRAVITY / v + friction + gFlux * gFlux * dvdn * gasRate) / denominator;
        }

        /** Permeable gas loss rate (1/s): vertical escape towards the vent plus lateral loss into the wall. */
        double outgassingRate(double zz, double p, double dpdz) {
            double threshold = c.percolationThreshold();
            if (alpha <= threshold || n <= 0) return 0;
            double connected = (alpha - threshold) / (1 - threshold);
            double k = c.referencePermeability() * connected * connected * connected;
            double depth = length - zz;
            // The connected network vents at the surface: its gas is driven by whichever is steeper,
            // the flow's own gradient or the excess over ambient across the depth to the vent.
            double gradient = Math.max(Math.abs(dpdz), Math.max(0, p - ambient) / Math.max(depth, r));
            double vertical = k / c.gasViscosity() * gradient / Math.max(depth, r);
            double pore = ambient + WATER_DENSITY * GRAVITY * Math.max(0, depth - in.waterTableDepthM());
            double lateral = 2 * Math.min(k, c.wallPermeability()) / c.gasViscosity()
                    * Math.max(0, p - pore) / (r * r * alpha);
            return vertical + lateral;
        }

        /**
         * Advances exsolution, outgassing and microlite growth over {@code h} metres towards their
         * equilibrium at {@code pEq}. Each relaxes exponentially, which stays stable however slowly
         * the magma rises. Uses the scratch fields of the last {@link #dpdz} call for the speeds.
         */
        void relax(State from, State to, double h, double zz, double pEq, double dpdz, boolean fragmented) {
            double meltAscent = gFlux * (1 - n) / (meltDensity * Math.max(1e-3, 1 - alpha));
            double exsolve = 1 - Math.exp(-h / (c.exsolutionTimescale() * Math.max(meltAscent, 1e-12)));
            double dew = Math.max(0, (waterExsolved(pEq) - from.ew) * exsolve);
            double dec = Math.max(0, (co2Exsolved(pEq) - from.ec) * exsolve);
            to.ew = from.ew + dew;
            to.ec = from.ec + dec;
            double nw = from.nw + (1 - segregated) * dew;
            double nc = from.nc + (1 - segregated) * dec;
            double lost = 0;
            if (!fragmented && meltAscent > 0) {
                // Gas leaves a rising parcel of melt for as long as that parcel takes to rise.
                double keep = Math.exp(-outgassingRate(zz, pEq, dpdz) * h / meltAscent);
                lost = (nw + nc) * (1 - keep);
                nw *= keep;
                nc *= keep;
            }
            to.nw = nw;
            to.nc = nc;
            to.lost = from.lost + lost;
            if (fragmented) {
                to.phi = from.phi;
            } else {
                double phiEq = Math.min(maxMicrolites, c.microlitesPerWtWater() * (c0 - dissolvedWater(to)));
                double grow = Math.exp(-h / (c.crystallisationTimescale() * Math.max(meltAscent, 1e-12)));
                to.phi = phiEq + (from.phi - phiEq) * grow;
            }
        }

        /**
         * Integrates the profile for mass flux per area {@code g}. Returns true when the flux is
         * admissible (reaches the vent at or above ambient pressure without choking below it).
         */
        boolean integrate(double g, Exit exit) {
            gFlux = g;
            segregated = segregation(g);
            State s = new State();
            State mid = new State();
            State next = new State();
            s.ew = gasWater;
            s.ec = gasCo2;
            s.nw = (1 - segregated) * gasWater;
            s.nc = (1 - segregated) * gasCo2;
            double p = in.chamberPressurePa();
            boolean fragmented = false;
            Fragmentation mode = Fragmentation.NONE;
            double fragmentationDepth = Double.NaN;
            double fragmentationViscosity = Double.NaN;
            double fragmentationCrystals = Double.NaN;

            double dpdz = dpdz(p, s, 0, false);
            if (choke) return false;
            double uPrev = u;
            int steps = z.length - 1;
            for (int i = 0; i < steps; i++) {
                double zz = z[i];
                double interval = z[i + 1] - z[i];
                while (zz < z[i + 1]) {
                    // Sub-step wherever the pressure would change by more than a few percent.
                    double dz = Math.min(z[i + 1] - zz,
                            Math.max(interval / 256, 0.05 * p / Math.max(Math.abs(dpdz), 1e-30)));
                    boolean last = i == steps - 1 && zz + dz >= z[i + 1];
                    double zMid = zz + 0.5 * dz;
                    double pm = p + 0.5 * dz * dpdz;
                    if (pm <= 0) return false;
                    relax(s, mid, 0.5 * dz, zz, pm, dpdz, fragmented);
                    double dpdzMid = dpdz(pm, mid, (mid.carried() - s.carried()) / (0.5 * dz), fragmented);
                    double rate = 0;
                    if (!choke) {
                        double pNext = p + dz * dpdzMid;
                        if (pNext < ambient) return false;
                        relax(s, next, dz, zMid, pm, dpdzMid, fragmented);
                        rate = (next.carried() - s.carried()) / dz;
                        double dpdzNext = dpdz(pNext, next, rate, fragmented);
                        if (!choke) {
                            dpdz = dpdzNext;
                            p = pNext;
                            zz = last ? z[i + 1] : zz + dz;
                            State t = s;
                            s = next;
                            next = t;
                        }
                    }
                    // Within a conduit radius of the vent the foam is open to the atmosphere and vents
                    // freely; whether it then breaks up is the jet's affair (see finish()).
                    if (!choke && !fragmented && length - zz > r) {
                        double expansion = (u - uPrev) / dz; // bubble growth rate in the rising parcel
                        double loss = outgassingRate(zz, p, dpdz);
                        boolean brittle = pureMeltViscosity * expansion >= c.brittleStressPa();
                        boolean foam = alpha >= c.fragmentationPorosity() && loss < expansion
                                && (pureMeltViscosity < INERTIAL_VISCOSITY
                                        || 4.0 / 3.0 * pureMeltViscosity * expansion * alpha >= c.foamStrengthPa());
                        if (brittle || foam) {
                            fragmented = true;
                            mode = pureMeltViscosity < INERTIAL_VISCOSITY ? Fragmentation.INERTIAL
                                    : (brittle ? Fragmentation.BRITTLE : Fragmentation.FOAM);
                            fragmentationDepth = length - zz;
                            fragmentationViscosity = Math.log10(meltViscosity);
                            fragmentationCrystals = Math.min(c.maxCrystalFraction(), phiChamber + s.phi);
                            dpdz = dpdz(p, s, rate, true); // continue with turbulent friction
                        }
                    }
                    if (choke) {
                        // The flow reached its sound speed: admissible only at the vent.
                        if (!last) return false;
                        fields(p, s);
                        return finish(exit, p, true, mode, fragmentationDepth, fragmentationViscosity,
                                fragmentationCrystals, s);
                    }
                    uPrev = u;
                }
            }
            return finish(exit, p, false, mode, fragmentationDepth, fragmentationViscosity, fragmentationCrystals, s);
        }

        private boolean finish(Exit exit, double p, boolean choked, Fragmentation mode, double fragmentationDepth,
                double fragmentationViscosity, double fragmentationCrystals, State s) {
            if (p < ambient) return false;
            if (exit != null) {
                exit.p = p;
                exit.u = u;
                // Near the sonic point the profile steepens faster than the grid resolves; a gas-bearing
                // flow within a few percent of its sound speed at the vent is choked.
                exit.choked = n > 0 && (choked || denominator < 0.05);
                exit.n = n;
                exit.alpha = alpha;
                exit.gasConstant = gasConstant;
                if (mode == Fragmentation.NONE && exit.choked) {
                    // A choked flow keeps decompressing above the vent; if that carries its gas past the
                    // fragmentation porosity, the jet fragments at the vent (Hawaiian fountains).
                    double gasAtAmbient = n * gasConstant * tK / ambient;
                    double alphaAtAmbient = gasAtAmbient / (gasAtAmbient + (1 - n) / meltDensity);
                    if (alphaAtAmbient >= c.fragmentationPorosity()) {
                        mode = pureMeltViscosity < INERTIAL_VISCOSITY ? Fragmentation.INERTIAL : Fragmentation.FOAM;
                        fragmentationDepth = 0;
                        fragmentationViscosity = Math.log10(meltViscosity);
                        fragmentationCrystals = Math.min(c.maxCrystalFraction(), phiChamber + s.phi);
                    }
                }
                exit.fragmentation = mode;
                exit.fragmentationDepth = fragmentationDepth;
                exit.meltViscosityLog10 = mode == Fragmentation.NONE ? Math.log10(meltViscosity) : fragmentationViscosity;
                exit.crystals = mode == Fragmentation.NONE
                        ? Math.min(c.maxCrystalFraction(), phiChamber + s.phi) : fragmentationCrystals;
                exit.dissolved = dissolved;
                exit.totalGas = s.ew + s.ec;
                exit.outgassed = exit.totalGas > 0 ? Math.min(1, s.lost / exit.totalGas) : 0;
            }
            return true;
        }

        /** Bisects (in log flux) between an admissible {@code lo} and an inadmissible {@code hi}. */
        double refine(double lo, double hi) {
            for (int i = 0; i < BISECTIONS; i++) {
                double mid = Math.sqrt(lo * hi);
                if (integrate(mid, null)) lo = mid;
                else hi = mid;
            }
            return lo;
        }

        ConduitSolution solution(double g) {
            Exit exit = new Exit();
            if (!integrate(g, exit)) {
                throw new IllegalStateException("mass flux " + g + " is not admissible");
            }
            double area = Math.PI * r * r;
            double massFlux = g * area;
            // volume as the chamber holds it: melt at the chamber's dissolved water
            double dre = massFlux * (1 - gasWater - gasCo2) / inletMelt;
            return new ConduitSolution(massFlux, dre, exit.u, exit.p, exit.choked, exit.n, exit.alpha,
                    exit.gasConstant, exit.fragmentation, exit.fragmentationDepth, exit.meltViscosityLog10,
                    exit.crystals, in.temperatureC(), exit.dissolved, exit.outgassed, segregated,
                    g / inletMelt, exit.totalGas);
        }
    }
}
