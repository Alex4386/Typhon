package me.alex4386.typhon.simulator.run;

import java.util.LinkedHashMap;
import java.util.Map;
import me.alex4386.typhon.engine.alert.EruptionStyle;
import me.alex4386.typhon.engine.assembly.VentPartition;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.subsurface.WaterBudget;
import me.alex4386.typhon.engine.geothermal.HydrothermalFeature;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * One row of the time series: the state of the primary volcano and the world at an engine step.
 *
 * @param values numeric columns in a stable order (booleans as 0/1, enums as ordinals)
 * @param alertLevel alert level name
 * @param style estimated eruption style name ({@code NONE} before the first estimate)
 */
public record Sample(long step, double timeSeconds, Map<String, Double> values, String alertLevel, String style) {

    public double get(String column) {
        Double v = values.get(column);
        return v == null ? Double.NaN : v;
    }

    public static Sample capture(Scenario scenario, EngineFrame frame) {
        VolcanoSystem volcano = scenario.volcano();
        MagmaChamber chamber = volcano.chamber();
        SeismicityModel seismic = volcano.seismicity();
        TephraSubsystem tephra = volcano.tephra();
        Geothermal geothermal = volcano.geothermal();
        LavaFlow lava = scenario.lava();

        Map<String, Double> v = new LinkedHashMap<>();
        v.put("overpressure_mpa", chamber.overpressureMPa());
        v.put("overpressure_rate_mpa_s", chamber.overpressureRateMPaPerSecond());
        v.put("eruption_rate_m3s", chamber.eruptionRate());
        v.put("erupted_volume_m3", chamber.eruptedVolume());
        v.put("supply_rate_m3s", chamber.supplyRate());
        v.put("chamber_temperature_c", chamber.temperatureC());
        v.put("silica_wt", chamber.silicaWt());
        v.put("water_wt", chamber.waterWt());
        v.put("crystal_fraction", chamber.crystalFraction());
        v.put("viscosity_log10", chamber.viscosityLog10());
        v.put("fragmented", bool(chamber.fragmented()));
        v.put("rsam", seismic.rsam());
        v.put("vt_per_min", seismic.vtRatePerMinute());
        v.put("lp_per_min", seismic.lpRatePerMinute());
        v.put("explosions_per_min", seismic.explosionRatePerMinute());
        v.put("tremor", bool(seismic.tremorActive()));
        v.put("swarm", bool(seismic.swarmActive()));
        v.put("alert_level", (double) volcano.alert().level().ordinal());
        EruptionStyle style = volcano.classifier().style();
        v.put("style", style == null ? -1.0 : style.ordinal());
        v.put("style_is_forecast", bool(volcano.classifier().isForecast()));
        v.put("vei", (double) volcano.classifier().vei());
        VentPartition.Result partition = volcano.coupler().partition();
        v.put("lava_share", partition == null ? 0 : partition.lavaShare());
        v.put("column_share", partition == null || partition.magmaMassFlux() <= 0 ? 0
                : partition.columnMassFlux() / partition.magmaMassFlux());
        v.put("collapse_fraction", partition == null ? 0 : partition.collapseFraction());
        v.put("water_magma_ratio", partition == null ? 0 : partition.waterMagmaRatio());
        v.put("effusing", bool(volcano.coupler().effusing()));
        v.put("explosive", bool(volcano.coupler().explosive()));
        v.put("lava_volume_m3", lava.totalLavaVolume());
        v.put("lava_active_cells", (double) lava.activeCellCount());
        v.put("lava_emitted_m3", lava.emittedVolume());
        v.put("lava_solidified_m3", lava.solidifiedVolume());
        v.put("plume_height_m", tephra.plumeHeight());
        TephraSubsystem.MassBudget budget = tephra.massBudget();
        v.put("ash_emitted_kg", budget.emitted());
        v.put("ash_airborne_kg", budget.airborne());
        v.put("ash_deposited_kg", budget.deposited());
        v.put("bombs_in_flight", (double) tephra.inFlightBombs());
        if (geothermal != null) {
            v.put("geothermal_activity", geothermal.activity());
            v.put("geothermal_max_excess_c", geothermal.grid().maxExcess());
            for (HydrothermalFeature kind : HydrothermalFeature.values()) {
                v.put("features_" + kind.name().toLowerCase(), (double) geothermal.count(kind));
            }
        }
        Subsurface subsurface = volcano.subsurface();
        if (subsurface != null) {
            WaterBudget water = subsurface.budget();
            v.put("water_rain_m3", water.rain());
            v.put("water_surface_m3", water.surfaceStorage());
            v.put("water_ground_m3", water.groundStorage());
            v.put("water_boiled_m3", water.boiled());
            v.put("water_springs_m3", subsurface.springDischarge());
            v.put("water_to_sea_m3", water.seaOutflow() + water.seaGroundwater());
            v.put("water_imbalance_m3", water.imbalance());
            double l = scenario.world().spec().metersPerColumn();
            int x = volcano.referencePoint().columnX(l);
            int z = volcano.referencePoint().columnZ(l);
            v.put("vent_water_table_depth_m", subsurface.waterTableDepthM(x, z));
            v.put("vent_ground_temperature_10m_c", subsurface.temperatureC(x, z, 10));
        }
        v.put("world_edits", (double) scenario.world().stacks().editCount());
        return new Sample(frame.step(), frame.time(), v, volcano.alert().level().name(),
                style == null ? "NONE" : style.name());
    }

    private static double bool(boolean b) {
        return b ? 1 : 0;
    }
}
