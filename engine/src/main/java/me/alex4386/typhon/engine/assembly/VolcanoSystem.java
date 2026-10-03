package me.alex4386.typhon.engine.assembly;

import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.alert.AlertConfig;
import me.alex4386.typhon.engine.alert.AlertLevelEstimator;
import me.alex4386.typhon.engine.deformation.DeformationConfig;
import me.alex4386.typhon.engine.deformation.DeformationModel;
import me.alex4386.typhon.engine.dike.DikeConfig;
import me.alex4386.typhon.engine.dike.DikeMagmaSource;
import me.alex4386.typhon.engine.dike.DikePropagation;
import me.alex4386.typhon.engine.geothermal.BlockPalette;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.geothermal.GeothermalBlocks;
import me.alex4386.typhon.engine.geothermal.GeothermalConfig;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.massflow.Lahars;
import me.alex4386.typhon.engine.massflow.MassFlowConfig;
import me.alex4386.typhon.engine.massflow.PyroclasticFlows;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.seismic.SeismicConfig;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.tephra.TephraConfig;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;

/**
 * One volcano: magma chamber, dikes, seismicity, alert level, surface coupling, tephra, pyroclastic
 * flows, lahars, geothermal activity and ground deformation, wired together and consistently scaled.
 *
 * <p>Terrain and lava are shared by every volcano in an engine, so the caller registers them:
 *
 * <pre>{@code
 * TerrainModel terrain = new TerrainModel();
 * LavaFlow lava = new LavaFlow(terrain);
 * VolcanoSystem volcano = VolcanoSystem.builder("fuji", vents, terrain, lava).build();
 * Engine.Builder engine = Engine.builder(seed).add(terrain);
 * volcano.addTo(engine);
 * engine.add(lava);
 * }</pre>
 */
public final class VolcanoSystem {
    private final String volcanoId;
    private final List<VentSite> vents;
    private final VolcanoScaling scaling;
    private final MagmaChamber chamber;
    private final DikePropagation dikes;
    private final SeismicityModel seismicity;
    private final AlertLevelEstimator alert;
    private final VolcanoCoupler coupler;
    private final TephraSubsystem tephra;
    private final Geothermal geothermal;
    private final PyroclasticFlows pdc;
    private final Lahars lahars;
    private final DeformationModel deformation;

    private VolcanoSystem(Builder b) {
        this.volcanoId = b.volcanoId;
        this.vents = b.vents;
        this.scaling = b.scaling;

        BlockPos primary = vents.get(0).position();
        MagmaChamberConfig chamberConfig = (b.chamberConfig != null
                        ? b.chamberConfig.toBuilder()
                        : MagmaChamberConfig.builder(volcanoId, defaultChamberCenter(primary)))
                .dormantTimeScale(scaling.dormantTimeCompression())
                .eruptiveTimeScale(scaling.eruptiveTimeCompression())
                .build();
        if (!chamberConfig.volcanoId().equals(volcanoId)) {
            throw new IllegalArgumentException("Chamber config is for volcano " + chamberConfig.volcanoId());
        }
        this.chamber = new MagmaChamber(chamberConfig);

        double failure = chamberConfig.tensileStrengthMPa();
        SeismicConfig seismicConfig = SeismicConfig.builder(volcanoId, primary).failureOverpressureMPa(failure).build();
        this.seismicity = new SeismicityModel(seismicConfig, chamber);
        this.alert = new AlertLevelEstimator(AlertConfig.defaults(volcanoId).withFailureOverpressure(failure), chamber, seismicity);

        if (b.dikes) {
            DikeConfig dikeConfig = (b.dikeConfig != null ? b.dikeConfig : DikeConfig.defaults()).withScaling(scaling);
            dikeConfig.timeScale = scaling.eruptiveTimeCompression();
            this.dikes = new DikePropagation(dikeConfig, DikeMagmaSource.of(chamber), b.terrain);
            dikes.setHypocenterListener(seismicity::queueInducedVt);
        } else {
            this.dikes = null;
        }

        TephraConfig tephraConfig = b.tephraConfig != null ? b.tephraConfig.copy() : new TephraConfig();
        tephraConfig.ballisticSpeedScale = scaling.velocityScale();
        tephraConfig.plumeHeightScale = scaling.plumeHeightScale();
        tephraConfig.massScale = scaling.volumeScale();
        if (b.windSet) {
            tephraConfig.initialWindSpeed = b.windSpeed * scaling.velocityScale();
            tephraConfig.initialWindDirectionRad = b.windBearing;
            tephraConfig.initialWindVariability = b.windVariability;
        }
        this.tephra = new TephraSubsystem("tephra:" + volcanoId, b.terrain, tephraConfig);

        if (b.geothermal) {
            GeothermalConfig geothermalConfig = b.geothermalConfig != null ? b.geothermalConfig : new GeothermalConfig();
            if (b.geothermalPrewarmSeconds >= 0) geothermalConfig.prewarmSeconds = b.geothermalPrewarmSeconds;
            BlockPos chamberCenter = chamberConfig.center();
            BlockPos center = b.geothermalCenter != null
                    ? b.geothermalCenter
                    : new BlockPos(chamberCenter.x(), primary.y(), chamberCenter.z());
            this.geothermal = new Geothermal(volcanoId, geothermalConfig, center, chamber, b.terrain, b.palette, vents);
        } else {
            this.geothermal = null;
        }

        if (b.massFlows) {
            MassFlowConfig pdcConfig = b.pdcConfig != null ? b.pdcConfig.copy() : MassFlowConfig.pdc();
            pdcConfig.metersPerBlock = scaling.metersPerBlock();
            MassFlowConfig laharConfig = b.laharConfig != null ? b.laharConfig.copy() : MassFlowConfig.lahar();
            laharConfig.metersPerBlock = scaling.metersPerBlock();
            this.pdc = new PyroclasticFlows(PyroclasticFlows.defaultId(volcanoId), b.terrain, pdcConfig);
            this.lahars = new Lahars(Lahars.defaultId(volcanoId), b.terrain, laharConfig);
            pdc.setDepositListener(lahars::addErodibleDeposit); // fresh ignimbrite is lahar source material
        } else {
            this.pdc = null;
            this.lahars = null;
        }

        if (b.deformation) {
            DeformationConfig deformationConfig = DeformationConfig.forChamber(chamberConfig, scaling);
            this.deformation = new DeformationModel(deformationConfig, chamber,
                    dikes != null ? dikes::geometries : List::of, b.terrain);
        } else {
            this.deformation = null;
        }

        this.coupler = new VolcanoCoupler(volcanoId, chamber, alert, vents, dikes, b.lava, tephra, pdc, geothermal,
                scaling, b.ballisticFraction);
    }

    /** Chamber a few dozen blocks under the primary vent, kept inside the overworld. */
    static BlockPos defaultChamberCenter(BlockPos vent) {
        return new BlockPos(vent.x(), Math.max(-56, vent.y() - 48), vent.z());
    }

    public static Builder builder(String volcanoId, List<VentSite> vents, TerrainModel terrain, LavaFlow lava) {
        return new Builder(volcanoId, vents, terrain, lava);
    }

    /**
     * Registers this volcano's subsystems in dependency order (chamber → dikes → seismicity → alert →
     * coupler → tephra → mass flows → geothermal → deformation). Listeners between subsystems are
     * attached at construction, so a restored engine must be built from a fresh {@code VolcanoSystem}.
     */
    public Engine.Builder addTo(Engine.Builder engine) {
        engine.add(chamber);
        if (dikes != null) engine.add(dikes);
        engine.add(seismicity).add(alert).add(coupler).add(tephra);
        if (pdc != null) engine.add(pdc).add(lahars);
        if (geothermal != null) engine.add(geothermal);
        if (deformation != null) engine.add(deformation);
        return engine;
    }

    /**
     * Changes the wind tephra is carried by: real speed (m/s, scaled by
     * {@link VolcanoScaling#velocityScale()}), bearing it blows towards and variability in [0, 1].
     * Applied on the tephra subsystem's next step.
     */
    public void setWind(double realSpeed, double bearingRad, double variability) {
        tephra.setWind(realSpeed * scaling.velocityScale(), bearingRad, variability);
    }

    public String volcanoId() { return volcanoId; }
    public List<VentSite> vents() { return vents; }
    public VolcanoScaling scaling() { return scaling; }
    public MagmaChamber chamber() { return chamber; }
    /** {@code null} when dikes are disabled. */
    public DikePropagation dikes() { return dikes; }
    /** {@code null} when mass flows are disabled. */
    public PyroclasticFlows pyroclasticFlows() { return pdc; }
    /** {@code null} when mass flows are disabled. */
    public Lahars lahars() { return lahars; }
    /** {@code null} when deformation is disabled. */
    public DeformationModel deformation() { return deformation; }
    public SeismicityModel seismicity() { return seismicity; }
    public AlertLevelEstimator alert() { return alert; }
    public VolcanoCoupler coupler() { return coupler; }
    public TephraSubsystem tephra() { return tephra; }
    /** {@code null} when geothermal activity is disabled. */
    public Geothermal geothermal() { return geothermal; }

    public static final class Builder {
        private final String volcanoId;
        private final List<VentSite> vents;
        private final TerrainModel terrain;
        private final LavaFlow lava;
        private VolcanoScaling scaling = VolcanoScaling.DEFAULT;
        private MagmaChamberConfig chamberConfig;
        private TephraConfig tephraConfig;
        private GeothermalConfig geothermalConfig;
        private BlockPalette palette = GeothermalBlocks.installFallbacks(BlockPalette.unrestricted());
        private boolean geothermal = true;
        private boolean dikes = true;
        private boolean massFlows = true;
        private boolean deformation = true;
        private DikeConfig dikeConfig;
        private MassFlowConfig pdcConfig;
        private MassFlowConfig laharConfig;
        private double ballisticFraction = 0.05;
        private boolean windSet;
        private double windSpeed;
        private double windBearing;
        private double windVariability;
        private BlockPos geothermalCenter;
        private double geothermalPrewarmSeconds = -1;

        private Builder(String volcanoId, List<VentSite> vents, TerrainModel terrain, LavaFlow lava) {
            this.volcanoId = Objects.requireNonNull(volcanoId, "volcanoId");
            this.vents = List.copyOf(vents);
            if (this.vents.isEmpty()) throw new IllegalArgumentException("A volcano needs at least one vent");
            this.terrain = Objects.requireNonNull(terrain, "terrain");
            this.lava = Objects.requireNonNull(lava, "lava");
        }

        public Builder scaling(VolcanoScaling scaling) { this.scaling = Objects.requireNonNull(scaling); return this; }
        /** Chamber parameters; its time scales are overridden by {@link #scaling}. */
        public Builder chamber(MagmaChamberConfig config) { this.chamberConfig = config; return this; }
        /** Tephra parameters; its scale factors are overridden by {@link #scaling}. */
        public Builder tephra(TephraConfig config) { this.tephraConfig = config; return this; }
        public Builder geothermal(GeothermalConfig config) { this.geothermalConfig = config; return this; }
        public Builder geothermalEnabled(boolean enabled) { this.geothermal = enabled; return this; }

        /**
         * Initial wind for tephra transport: real speed (m/s, converted with
         * {@link VolcanoScaling#velocityScale()}), bearing it blows towards (radians from +X towards
         * +Z) and variability in [0, 1].
         */
        public Builder wind(double realSpeed, double bearingRad, double variability) {
            if (!(realSpeed >= 0)) throw new IllegalArgumentException("wind speed must be >= 0");
            this.windSet = true;
            this.windSpeed = realSpeed;
            this.windBearing = bearingRad;
            this.windVariability = variability;
            return this;
        }

        /**
         * Centre of the geothermal grid. Defaults to the volcano centre: above the magma chamber, at the
         * primary vent's height.
         */
        public Builder geothermalCenter(BlockPos center) { this.geothermalCenter = Objects.requireNonNull(center); return this; }

        /**
         * Model seconds of geothermal spin-up run once on the first step with terrain (overrides
         * {@link GeothermalConfig#prewarmSeconds}), so the volcano starts with a developed
         * hydrothermal system.
         */
        public Builder geothermalPrewarm(double seconds) {
            if (!(seconds >= 0)) throw new IllegalArgumentException("prewarm seconds must be >= 0");
            this.geothermalPrewarmSeconds = seconds;
            return this;
        }
        public Builder dikesEnabled(boolean enabled) { this.dikes = enabled; return this; }
        public Builder massFlowsEnabled(boolean enabled) { this.massFlows = enabled; return this; }
        public Builder deformationEnabled(boolean enabled) { this.deformation = enabled; return this; }
        /** Dike parameters; length scale and time compression are overridden by {@link #scaling}. */
        public Builder dikes(DikeConfig config) { this.dikeConfig = config; return this; }
        /** Mass-flow parameters; {@code metersPerBlock} is overridden by {@link #scaling}. */
        public Builder pyroclasticFlows(MassFlowConfig config) { this.pdcConfig = config; return this; }
        public Builder lahars(MassFlowConfig config) { this.laharConfig = config; return this; }
        /** Blocks the host supports; unsupported ones fall back along the default chains. */
        public Builder palette(BlockPalette palette) { this.palette = Objects.requireNonNull(palette); return this; }
        public Builder ballisticFraction(double fraction) { this.ballisticFraction = fraction; return this; }

        public VolcanoSystem build() {
            return new VolcanoSystem(this);
        }
    }
}
