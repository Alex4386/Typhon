package me.alex4386.typhon.engine.subsurface;

/**
 * Tunables of the world-level subsurface model ({@link Subsurface}): heat conduction, groundwater
 * and surface water. Fields are public and mutable (bound from the {@code subsurface:} section of
 * {@code world.yaml}); the climate, geotherm and aquifer sections of the world definition fill the
 * corresponding fields.
 *
 * <p>All values are SI / real units, in physical seconds. Heat and groundwater act over months to
 * years, so worlds usually spin them up ({@link Subsurface#equilibrate}); with adaptive engine steps a
 * quiet world advances them hours at a time.
 */
public final class SubsurfaceConfig {
    // ── Vertical grid ──
    /** Number of terrain-following levels per solver column. */
    public int levels = 24;
    /** Thickness of the top level (m). */
    public double firstLevelM = 1.0;
    /** Geometric growth of level thickness with depth (24 levels at 1.32 reach ≈ 2.4 km). */
    public double levelGrowth = 1.32;

    // ── Time ──
    /** Seconds between heat/groundwater steps (macro step); longer engine steps run longer macro steps. */
    public double macroStepSeconds = 120;
    /** Seconds between surface-water steps (sub-stepped internally for stability). */
    public double surfaceWaterStepSeconds = 1;
    /** Longest single heat/groundwater solve (s); longer spans are split (groundwater sub-steps further). */
    public double maxMacroSpanSeconds = 7 * 86_400;
    /**
     * Longest engine step while surface water moves (s). The flow sub-steps at its own CFL step
     * whatever the engine step; this only bounds how long other subsystems (erosion, lahars) see the
     * same water. A spring keeps water moving for good, so a quiet volcano with springs still takes
     * steps of up to this.
     */
    public double movingWaterStepSeconds = 86_400;
    /**
     * Surface-water routing per engine step at most (s). Shallow flows reach a quasi-steady state
     * within minutes; a longer step routes this long and keeps the flow field, while sources,
     * infiltration and evaporation still integrate over the whole step. Scale knob: raise it to route
     * long steps in full (cost grows with it).
     */
    public double surfaceWaterRoutingSeconds = 300;

    // ── Thermal ──
    /** Mean annual surface temperature (°C); upper boundary of conduction. */
    public double surfaceTemperatureC = 15;
    /** Background conductive geotherm (°C/km). */
    public double gradientCPerKm = 30;
    /**
     * Linearised heat-exchange coefficient between the ground surface and the air (W/m²K), lumping
     * sensible, latent and radiative losses (≈10–30 W/m²K for bare ground).
     */
    public double surfaceExchangeWm2K = 20;
    /** Exchange coefficient under standing water (W/m²K): far larger (convecting water). */
    public double waterExchangeWm2K = 500;
    /** Latent heat of melting used as apparent heat capacity between solidus and liquidus (J/kg). */
    public double latentHeatMeltJkg = 4.0e5;
    /** Critical Rayleigh number for onset of convection in a porous layer (Horton–Rogers–Lapwood: 4π² ≈ 40). */
    public double criticalRayleigh = 40;
    /** Thermal expansivity of water (1/K) in the porous Rayleigh number. */
    public double waterExpansivity = 7e-4;
    /** Upper limit of the convective Nusselt enhancement of conductivity. */
    public double maxNusselt = 200;

    // ── Water ──
    /** Rainfall (mm/h) over the whole world. */
    public double rainfallMmPerHour = 0;
    /** Evaporation from standing water (mm/h). */
    public double evaporationMmPerHour = 0.1;
    /** Initial depth of the water table below the ground (m). */
    public double initialWaterTableDepthM = 20;
    /** Specific yield (drainable porosity) of the aquifer. */
    public double specificYield = 0.1;
    /**
     * How closely the initial water table follows the topography: 1 keeps it
     * {@link #initialWaterTableDepthM} below the ground everywhere, 0 makes it flat at the base level
     * (very permeable ground such as young basalt). Real water tables are subdued replicas of the
     * topography (≈0.3–0.8; Haitjema &amp; Mitchell-Bruker 2005).
     */
    public double waterTableTopographyFactor = 1.0;
    /** Base level (m) the initial water table relaxes to; {@code NaN} = sea level, or the lowest ground. */
    public double waterTableBaseLevelM = Double.NaN;
    /** Fraction of infiltrating rain that reaches the water table (the rest is evapotranspired). */
    public double rechargeFraction = 1.0;
    /** e-folding time (s) of drainage from the vadose zone to the water table. */
    public double vadoseLagSeconds = 86400;
    /** Manning roughness of the ground for surface water (s/m^⅓). */
    public double manningN = 0.035;
    /** Water thinner than this (m) is treated as a wet film: it infiltrates/evaporates but does not flow. */
    public double minFlowDepthM = 1e-3;
    /**
     * Imported water bodies of at least this many columns are fixed-level reservoirs (like open water)
     * instead of simulated lakes: their level changes negligibly over play time, and a deep lake would
     * force tiny shallow-water steps over its whole area.
     */
    public int reservoirColumns = 4096;
    /** Courant number of the local-inertial surface-water scheme (Bates et al. 2010: 0.7). */
    public double surfaceWaterCfl = 0.7;
    /** Iterations of the red-black SOR groundwater solve per macro step. */
    public int groundwaterIterations = 60;
    /**
     * Shortest groundwater sub-step (physical s). A macro step whose SOR solve does not converge
     * within {@link #groundwaterIterations} is halved until it does, down to this: unconverged around
     * strong sinks (boiling) in permeable rock, the flux update overshoots heads below the floor and
     * above the ground (springs from nothing). Young basalt converges at ~2-day steps.
     */
    public double minGroundwaterStepSeconds = 86400;
    /**
     * Columns on the edge of the modelled area keep their water table: the regional aquifer beyond
     * a window over a larger volcano, which recharge and drainage keep at its level. Off, the edge is
     * a no-flow boundary (an island or a closed basin).
     */
    public boolean regionalBoundary = false;
    /** SOR over-relaxation factor. */
    public double sorOmega = 1.7;
    /** Latent heat of vaporisation of water (J/kg). */
    public double latentHeatVaporJkg = 2.26e6;
    /** e-folding time (s) of steam-zone collapse (condensation, refilling) once a cell is below boiling. */
    public double steamCollapseSeconds = 7 * 86400;

    // ── Performance ──
    /**
     * Threads for the heat solver (0 = all processors). Results are identical for any value: each
     * parallel task only writes its own chunk.
     */
    public int threads = 0;

    // ── Level of detail ──
    /**
     * Largest temperature change (°C) during a chunk's last step that keeps it HOT. Chunks whose
     * temperatures have settled (steady conduction or convection) stop being stepped.
     */
    public double hotChangeC = 0.05;
    /** WARM chunks (neighbours of HOT ones) are stepped every this many macro steps. */
    public int warmEvery = 10;
    /** Macro steps a chunk stays at a level after it stopped qualifying (hysteresis). */
    public int demoteAfter = 20;

    public void validate() {
        if (levels < 2 || levels > 64) throw new IllegalArgumentException("levels must be in [2, 64]");
        if (!(firstLevelM > 0) || !(levelGrowth >= 1)) throw new IllegalArgumentException("bad level geometry");
        if (!(macroStepSeconds > 0) || !(surfaceWaterStepSeconds > 0) || !(maxMacroSpanSeconds > 0)
                || !(movingWaterStepSeconds > 0) || !(surfaceWaterRoutingSeconds > 0)) {
            throw new IllegalArgumentException("time steps must be > 0");
        }
        if (rainfallMmPerHour < 0 || evaporationMmPerHour < 0) throw new IllegalArgumentException("negative rates");
        if (!(specificYield > 0 && specificYield <= 1)) throw new IllegalArgumentException("specificYield must be in (0, 1]");
        if (!(waterTableTopographyFactor >= 0 && waterTableTopographyFactor <= 1)) {
            throw new IllegalArgumentException("waterTableTopographyFactor must be in [0, 1]");
        }
        if (!(rechargeFraction >= 0 && rechargeFraction <= 1)) {
            throw new IllegalArgumentException("rechargeFraction must be in [0, 1]");
        }
        if (!(vadoseLagSeconds > 0)) throw new IllegalArgumentException("vadoseLagSeconds must be > 0");
        if (!(manningN > 0) || !(surfaceWaterCfl > 0 && surfaceWaterCfl <= 1)) {
            throw new IllegalArgumentException("bad surface-water parameters");
        }
        if (groundwaterIterations < 1 || !(sorOmega > 0 && sorOmega < 2) || !(minGroundwaterStepSeconds > 0)) {
            throw new IllegalArgumentException("bad groundwater solver parameters");
        }
        if (warmEvery < 1 || demoteAfter < 0) throw new IllegalArgumentException("bad LOD parameters");
    }

    public SubsurfaceConfig copy() {
        SubsurfaceConfig c = new SubsurfaceConfig();
        for (java.lang.reflect.Field f : SubsurfaceConfig.class.getFields()) {
            try {
                f.set(c, f.get(this));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return c;
    }
}
