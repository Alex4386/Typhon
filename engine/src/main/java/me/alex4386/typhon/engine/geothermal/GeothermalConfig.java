package me.alex4386.typhon.engine.geothermal;

import java.util.HashSet;
import java.util.Set;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * Tunables of the geothermal subsystem. Fields are public and mutable; adjust before constructing
 * {@link Geothermal} (it reads them on every step, so later changes also take effect).
 *
 * <p>Temperatures are absolute °C of the shallow subsurface represented by each grid cell. Formation
 * rates are expected occurrences per grid cell per simulated hour at full strength; with the default
 * {@link #timeScale} of 1 features accumulate over hours of play, not seconds.
 */
public final class GeothermalConfig {
    // ── Grid & time ──
    /** Half-extent of the square grid around the volcano centre, in blocks. */
    public int radius = 128;
    /** Edge length of one grid cell, in blocks. */
    public int cellSize = 4;
    /** Simulated seconds between steps. */
    public double stepSeconds = 2.0;
    /** Time compression: every step advances the geothermal model by {@code dt × timeScale}. */
    public double timeScale = 1.0;

    // ── Heat ──
    public double ambientC = 15.0;
    /** Effective (advective, hydrothermal) diffusivity in m²/s; much larger than rock conduction. */
    public double diffusivity = 0.05;
    /** Surface heat-loss rate (1/s); sets the e-folding time of a cooling cell (default 2 h). */
    public double surfaceLossPerSecond = 1.0 / 7200.0;
    /** Peak heating rate (°C/s) at a vent for activity 1. */
    public double ventHeatRate = 0.06;
    /** Extra Gaussian halo (blocks) added to a vent's radius for its heat footprint. */
    public double ventHaloBlocks = 8.0;
    /** Heating rate (°C/s) directly above the chamber for activity 1. */
    public double chamberHeatRate = 0.02;
    public double minChamberDepth = 20.0;
    /** Depth (m) of the shallow layer that absorbs heat from lava resting on the surface. */
    public double lavaCouplingDepth = 10.0;

    // ── Activity from the magma system ──
    public double activityMinChamberC = 600.0;
    public double activityFullChamberC = 1100.0;
    public double overpressureFullMPa = 10.0;
    public double eruptionRateFull = 10.0;

    // ── Groundwater ──
    /** Saturation of a dry-land cell level with its surroundings. */
    public double baseSaturation = 0.45;
    /** Extra saturation per block a cell lies below the local mean ground height. */
    public double elevationSaturationPerBlock = 0.04;
    /** Relaxation rate (1/s) of saturation towards its terrain-derived target. */
    public double rechargePerSecond = 1.0 / 1800.0;
    /** Boil-off rate (1/s) per 100 °C above boiling; hot cells become vapour-dominated. */
    public double boilOffPerSecond = 1.0 / 3600.0;

    // ── Fumaroles & sulfur ──
    public double fumaroleMinC = 100.0;
    public double fumaroleFullC = 350.0;
    public double fumaroleFormationPerHour = 0.5;
    public int maxFumaroles = 48;
    public int fumaroleSpacing = 3;
    /** Sulfur crust / spike growth events per fumarole per hour at intensity 1. */
    public double sulfurDepositPerHour = 2.0;
    public int sulfurDepositRadius = 2;
    public int maxSpikeHeight = 3;

    // ── Geysers (vanilla mechanic) ──
    public double geyserMinC = 95.0;
    public double geyserMaxC = 200.0;
    public double geyserMinWater = 0.6;
    public double geyserFormationPerHour = 0.05;
    public int maxGeysers = 6;
    public int geyserSpacing = 10;

    // ── Springs & mud ──
    public double hotSpringMinC = 40.0;
    public double hotSpringMaxC = 95.0;
    public double hotSpringMinWater = 0.5;
    /** Springs at or above this temperature become sulfur springs (potent sulfur floor). */
    public double sulfurSpringMinC = 70.0;
    public double hotSpringFormationPerHour = 0.08;
    public int maxHotSprings = 10;
    public int hotSpringSpacing = 6;

    public double mudPotMinC = 70.0;
    public double mudPotMaxC = 110.0;
    public double mudPotMinWater = 0.3;
    public double mudPotMaxWater = 0.7;
    public double mudPotFormationPerHour = 0.1;
    public int maxMudPots = 12;
    public int mudPotSpacing = 4;

    // ── Submarine vents ──
    public double submarineVentMinC = 120.0;
    public double submarineVentFormationPerHour = 0.1;
    public int maxSubmarineVents = 12;
    public int submarineVentSpacing = 4;

    // ── Alteration ──
    public double acidMinC = 100.0;
    public double acidMaxWater = 0.65;
    public double acidAlterationPerHour = 1.0;
    public double sinterMinC = 60.0;
    public double sinterMaxC = 180.0;
    public double sinterMinWater = 0.5;
    public double sinterPerHour = 0.6;
    /** Low-temperature epithermal band in which cinnabar (HgS) precipitates. */
    public double cinnabarMinC = 80.0;
    public double cinnabarMaxC = 160.0;
    public double cinnabarMinWater = 0.25;
    public double cinnabarPerHour = 0.15;

    // ── Gas hazards ──
    /** Seconds (unscaled) between gas hazard updates. */
    public double hazardIntervalSeconds = 10.0;
    /** Total gas concentration (ppm) above a fumarole cell of intensity 1. */
    public double gasFluxPpm = 2000.0;
    public double minHazardPpm = 1.0;

    /** Surface blocks hydrothermal processes may replace. */
    public Set<BlockId> alterableSurfaces = new HashSet<>(GeothermalBlocks.DEFAULT_ALTERABLE);

    public int intervalTicks() {
        return Math.max(1, (int) Math.round(stepSeconds * 20));
    }

    void validate() {
        if (radius < 1) throw new IllegalArgumentException("radius must be >= 1");
        if (cellSize < 1) throw new IllegalArgumentException("cellSize must be >= 1");
        if (stepSeconds <= 0) throw new IllegalArgumentException("stepSeconds must be > 0");
        if (timeScale <= 0) throw new IllegalArgumentException("timeScale must be > 0");
        if (diffusivity < 0 || surfaceLossPerSecond < 0) throw new IllegalArgumentException("negative heat rate");
        if (fumaroleFullC <= fumaroleMinC) throw new IllegalArgumentException("fumaroleFullC must exceed fumaroleMinC");
        if (activityFullChamberC <= activityMinChamberC) {
            throw new IllegalArgumentException("activityFullChamberC must exceed activityMinChamberC");
        }
    }
}
