package me.alex4386.typhon.engine.assembly;

import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.alert.AlertConfig;
import me.alex4386.typhon.engine.alert.AlertLevelEstimator;
import me.alex4386.typhon.engine.alert.EruptionClassifier;
import me.alex4386.typhon.engine.deformation.DeformationConfig;
import me.alex4386.typhon.engine.deformation.DeformationModel;
import me.alex4386.typhon.engine.dike.DikeConfig;
import me.alex4386.typhon.engine.dike.DikeMagmaSource;
import me.alex4386.typhon.engine.dike.DikePropagation;
import me.alex4386.typhon.engine.geomorph.ChamberRoof;
import me.alex4386.typhon.engine.geomorph.GeomorphConfig;
import me.alex4386.typhon.engine.geomorph.Geomorphology;
import me.alex4386.typhon.engine.geomorph.GroundState;
import me.alex4386.typhon.engine.geothermal.BlockPalette;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.subsurface.SubsurfaceConfig;
import me.alex4386.typhon.engine.geothermal.GeothermalBlocks;
import me.alex4386.typhon.engine.geothermal.GeothermalConfig;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.massflow.DebrisAvalanches;
import me.alex4386.typhon.engine.massflow.Lahars;
import me.alex4386.typhon.engine.massflow.MassFlowConfig;
import me.alex4386.typhon.engine.massflow.PyroclasticFlows;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.seismic.SeismicConfig;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.tephra.TephraConfig;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;

/**
 * One volcano: magma chamber, dikes, seismicity, alert level, surface coupling, tephra, pyroclastic
 * flows, lahars, geothermal activity and ground deformation, wired together and consistently scaled.
 *
 * <p>Terrain and lava are shared by every volcano in an engine, so the caller registers them. The
 * lava grid takes its scale from the volcano's {@link VolcanoScaling#metersPerBlock()}; since the
 * field is shared, every volcano using it must have the same {@code metersPerBlock}.
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
    private final EruptionClassifier classifier;
    private final VolcanoCoupler coupler;
    private final TephraSubsystem tephra;
    private final Geothermal geothermal;
    private final Subsurface subsurface;
    /** Whether {@link #subsurface} was created for this volcano alone (registered with it). */
    private final boolean ownsSubsurface;
    private final PyroclasticFlows pdc;
    private final Lahars lahars;
    private final DebrisAvalanches avalanches;
    private final Geomorphology geomorphology;
    private final DeformationModel deformation;
    private final VolcanoUnits units;
    private final VolcanoDetail detail;

    private final double ballisticFraction;

    private VolcanoSystem(Builder b) {
        this.volcanoId = b.volcanoId;
        this.ballisticFraction = b.ballisticFraction;
        this.vents = b.vents;
        this.scaling = b.scaling;
        b.lava.setMetersPerBlock(scaling.metersPerBlock());
        b.terrain.setMetersPerBlock(scaling.metersPerBlock());

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
        // The lava this volcano erupts lives on the volcano's clock: emplaced at the eruptive time
        // compression, cooling on afterwards at the dormant one.
        MagmaChamber clockChamber = chamber;
        double eruptive = chamberConfig.eruptiveTimeScale();
        double dormant = chamberConfig.dormantTimeScale();
        b.lava.registerClock(volcanoId, () -> clockChamber.erupting() ? eruptive : dormant);

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

        if (b.subsurface != null) {
            this.subsurface = b.subsurface;
            this.ownsSubsurface = false;
        } else if (b.geothermal) {
            this.subsurface = new Subsurface(b.terrain.world(),
                    b.subsurfaceConfig != null ? b.subsurfaceConfig.copy() : defaultSubsurfaceConfig(scaling));
            this.ownsSubsurface = true;
        } else {
            this.subsurface = null;
            this.ownsSubsurface = false;
        }

        if (b.geothermal) {
            GeothermalConfig geothermalConfig = b.geothermalConfig != null ? b.geothermalConfig : new GeothermalConfig();
            if (b.geothermalPrewarmSeconds >= 0) geothermalConfig.prewarmSeconds = b.geothermalPrewarmSeconds;
            BlockPos chamberCenter = chamberConfig.center();
            BlockPos center = b.geothermalCenter != null
                    ? b.geothermalCenter
                    : new BlockPos(chamberCenter.x(), primary.y(), chamberCenter.z());
            this.geothermal = new Geothermal(volcanoId, geothermalConfig, center, chamber, b.terrain, b.palette, vents,
                    subsurface);
            subsurface.setHeatSources(volcanoId, geothermal);
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
            // loose ignimbrite and tephra in the world model are lahar source material automatically
            MassFlowConfig avalancheConfig = b.avalancheConfig != null ? b.avalancheConfig.copy() : MassFlowConfig.debrisAvalanche();
            avalancheConfig.metersPerBlock = scaling.metersPerBlock();
            this.avalanches = b.geomorphology
                    ? new DebrisAvalanches(DebrisAvalanches.defaultId(volcanoId), b.terrain, avalancheConfig) : null;
        } else {
            this.pdc = null;
            this.lahars = null;
            this.avalanches = null;
        }

        if (b.deformation) {
            DeformationConfig deformationConfig = DeformationConfig.forChamber(chamberConfig, scaling);
            deformationConfig.stations = new java.util.ArrayList<>(b.stations);
            this.deformation = new DeformationModel(deformationConfig, chamber,
                    dikes != null ? dikes::geometries : List::of, b.terrain);
        } else {
            this.deformation = null;
        }

        this.coupler = new VolcanoCoupler(volcanoId, chamber, seismicity, vents, dikes, b.terrain, b.lava, tephra, pdc,
                geothermal, scaling, b.ballisticFraction);

        if (subsurface != null) coupler.setGround(subsurface);
        this.classifier = new EruptionClassifier(volcanoId, chamber, coupler);
        alert.setClassifier(classifier);

        // Every deposit is attributed to this volcano's current eruption (stratigraphy).
        this.units = new VolcanoUnits(b.terrain.world(), volcanoId, chamber);
        me.alex4386.typhon.engine.world.SurfaceDetailConfig detailConfig = b.detailConfig != null ? b.detailConfig
                : me.alex4386.typhon.engine.world.SurfaceDetailConfig.defaults(scaling.metersPerBlock(),
                        vents.get(0).craterRadius() * scaling.metersPerBlock());
        this.detail = detailConfig.enabled()
                ? new VolcanoDetail(volcanoId, detailConfig, b.terrain.world(), primary, b.detailRelief) : null;
        coupler.setUnits(units);
        tephra.setUnits(units);
        tephra.setMoltenSurface((x, z) -> b.lava.thickness(x, z) > 0 || b.lava.crustThickness(x, z) > 0);
        if (dikes != null) dikes.setUnits(units);
        if (pdc != null) {
            pdc.setUnits(units);
            lahars.setUnits(units);
        }
        if (avalanches != null) avalanches.setUnits(units);

        // Slopes, craters and collapse: fed by explosions and earthquakes, acting on the world model.
        if (b.geomorphology) {
            this.geomorphology = new Geomorphology(Geomorphology.defaultId(volcanoId), volcanoId, b.terrain,
                    b.geomorphConfig != null ? b.geomorphConfig : new GeomorphConfig());
            geomorphology.setUnits(units);
            geomorphology.setGround(GroundState.of(subsurface));
            geomorphology.setFlows(avalanches, lahars, pdc);
            geomorphology.setChamber(ChamberRoof.of(chamber));
            geomorphology.setTimeScale(() -> clockChamber.erupting() ? eruptive : dormant);
            geomorphology.setVents(vents, clockChamber::erupting);
            Geomorphology g = geomorphology;
            seismicity.setQuakeListener(e -> g.queueQuake(e.hypocenter(), e.magnitude()));
            coupler.setExplosionListener(g::queueExplosion);
        } else {
            this.geomorphology = null;
        }

        // Surface processes exchange heat and water with the ground model (when there is one):
        // cooling lava and hot deposits heat it, dikes heat it at depth, lava boils standing water.
        if (subsurface != null) {
            b.lava.setGround(subsurface);
            if (dikes != null) dikes.setGround(subsurface);
            if (pdc != null) {
                pdc.setGround(subsurface);
                lahars.setGround(subsurface);
            }
            if (avalanches != null) avalanches.setGround(subsurface);
        }
    }

    /**
     * Subsurface defaults for a volcano that runs its own (no world): heat and groundwater advance at
     * the dormant time compression, so hydrothermal systems develop over hours of play.
     */
    public static SubsurfaceConfig defaultSubsurfaceConfig(VolcanoScaling scaling) {
        SubsurfaceConfig config = new SubsurfaceConfig();
        config.timeScale = scaling.dormantTimeCompression();
        return config;
    }

    /** Chamber a few dozen blocks under the primary vent, kept inside the overworld. */
    public static BlockPos defaultChamberCenter(BlockPos vent) {
        return new BlockPos(vent.x(), Math.max(-56, vent.y() - 48), vent.z());
    }

    public static Builder builder(String volcanoId, List<VentSite> vents, TerrainModel terrain, LavaFlow lava) {
        return new Builder(volcanoId, vents, terrain, lava);
    }

    /**
     * Registers this volcano's subsystems in dependency order (chamber → dikes → seismicity → alert →
     * coupler → style estimate → tephra → mass flows → geomorphology → debris avalanches → geothermal →
     * deformation). Listeners between subsystems are
     * attached at construction, so a restored engine must be built from a fresh {@code VolcanoSystem}.
     */
    public Engine.Builder addTo(Engine.Builder engine) {
        for (Subsystem subsystem : subsystems()) engine.add(subsystem);
        return engine;
    }

    /** This volcano's subsystems in registration order. */
    public List<Subsystem> subsystems() {
        List<Subsystem> list = new java.util.ArrayList<>();
        if (ownsSubsurface) list.add(subsurface);
        list.add(chamber);
        if (dikes != null) list.add(dikes);
        list.add(seismicity);
        list.add(alert);
        list.add(coupler);
        list.add(classifier);
        list.add(tephra);
        if (pdc != null) {
            list.add(pdc);
            list.add(lahars);
        }
        if (geomorphology != null) list.add(geomorphology);
        if (avalanches != null) list.add(avalanches);
        if (geothermal != null) list.add(geothermal);
        if (deformation != null) list.add(deformation);
        if (detail != null) list.add(detail);
        return list;
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
    /** Configured ballistic share; no longer used (ballistics follow clast physics). */
    public double ballisticFraction() { return ballisticFraction; }
    public List<VentSite> vents() { return vents; }
    public VolcanoScaling scaling() { return scaling; }
    public MagmaChamber chamber() { return chamber; }
    /** Attributes this volcano's deposits to its eruptions. */
    public VolcanoUnits units() { return units; }
    /** {@code null} when dikes are disabled. */
    public DikePropagation dikes() { return dikes; }
    /** {@code null} when mass flows are disabled. */
    public PyroclasticFlows pyroclasticFlows() { return pdc; }
    /** {@code null} when mass flows are disabled. */
    public Lahars lahars() { return lahars; }
    /** {@code null} when mass flows or geomorphology are disabled. */
    public DebrisAvalanches debrisAvalanches() { return avalanches; }
    /** Slope failure, craters and collapse; {@code null} when disabled. */
    public Geomorphology geomorphology() { return geomorphology; }
    /** {@code null} when deformation is disabled. */
    public DeformationModel deformation() { return deformation; }
    public SeismicityModel seismicity() { return seismicity; }
    public AlertLevelEstimator alert() { return alert; }
    /** Estimated eruption style and VEI (output only). */
    public EruptionClassifier classifier() { return classifier; }
    public VolcanoCoupler coupler() { return coupler; }
    public TephraSubsystem tephra() { return tephra; }
    /** {@code null} when geothermal activity is disabled. */
    public Geothermal geothermal() { return geothermal; }
    /** The subsurface model this volcano heats ({@code null} without geothermal activity and no world). */
    public Subsurface subsurface() { return subsurface; }
    /** The crater-resolving fine surface around the primary vent, {@code null} when disabled. */
    public me.alex4386.typhon.engine.world.SurfaceDetail surfaceDetail() { return detail == null ? null : detail.detail(); }
    /** Its configuration ({@link me.alex4386.typhon.engine.world.SurfaceDetailConfig#DISABLED} when off). */
    public me.alex4386.typhon.engine.world.SurfaceDetailConfig surfaceDetailConfig() {
        return detail == null ? me.alex4386.typhon.engine.world.SurfaceDetailConfig.DISABLED : (me.alex4386.typhon.engine.world.SurfaceDetailConfig) detail.config();
    }

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
        private boolean geomorphology = true;
        private GeomorphConfig geomorphConfig;
        private MassFlowConfig avalancheConfig;
        private List<me.alex4386.typhon.engine.deformation.GeodeticStation> stations = List.of();
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
        private Subsurface subsurface;
        private SubsurfaceConfig subsurfaceConfig;
        private me.alex4386.typhon.engine.world.SurfaceDetailConfig detailConfig;
        private java.util.function.DoubleBinaryOperator detailRelief;

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
         * Physical seconds of subsurface spin-up run once on the first step with terrain (overrides
         * {@link GeothermalConfig#prewarmSeconds}), so the volcano starts with a developed
         * hydrothermal system.
         */
        public Builder geothermalPrewarm(double seconds) {
            if (!(seconds >= 0)) throw new IllegalArgumentException("prewarm seconds must be >= 0");
            this.geothermalPrewarmSeconds = seconds;
            return this;
        }
        /**
         * The world's shared subsurface model (registered by the world). Without one, a volcano with
         * geothermal activity creates and registers its own.
         */
        public Builder subsurface(Subsurface subsurface) { this.subsurface = subsurface; return this; }
        /** Parameters of the subsurface model this volcano creates when no shared one is given. */
        public Builder subsurfaceConfig(SubsurfaceConfig config) { this.subsurfaceConfig = config; return this; }
        public Builder dikesEnabled(boolean enabled) { this.dikes = enabled; return this; }
        public Builder massFlowsEnabled(boolean enabled) { this.massFlows = enabled; return this; }
        public Builder deformationEnabled(boolean enabled) { this.deformation = enabled; return this; }
        /** Slope stability, mass wasting, craters and caldera collapse (on by default). */
        public Builder geomorphologyEnabled(boolean enabled) { this.geomorphology = enabled; return this; }
        public Builder geomorphology(GeomorphConfig config) { this.geomorphConfig = config; return this; }
        /** Debris-avalanche parameters; {@code metersPerBlock} is overridden by {@link #scaling}. */
        public Builder debrisAvalanches(MassFlowConfig config) { this.avalancheConfig = config; return this; }
        /** Virtual GNSS/tilt stations sampled by the deformation model (world columns). */
        public Builder stations(List<me.alex4386.typhon.engine.deformation.GeodeticStation> stations) {
            this.stations = List.copyOf(stations);
            return this;
        }
        /** Dike parameters; length scale and time compression are overridden by {@link #scaling}. */
        public Builder dikes(DikeConfig config) { this.dikeConfig = config; return this; }
        /** Mass-flow parameters; {@code metersPerBlock} is overridden by {@link #scaling}. */
        public Builder pyroclasticFlows(MassFlowConfig config) { this.pdcConfig = config; return this; }
        public Builder lahars(MassFlowConfig config) { this.laharConfig = config; return this; }
        /** Blocks the host supports; unsupported ones fall back along the default chains. */
        public Builder palette(BlockPalette palette) { this.palette = Objects.requireNonNull(palette); return this; }
        public Builder ballisticFraction(double fraction) { this.ballisticFraction = fraction; return this; }
        /** Fine surface around the primary vent; {@code null} (the default) derives it from the crater size. */
        public Builder detail(me.alex4386.typhon.engine.world.SurfaceDetailConfig config) { this.detailConfig = config; return this; }
        /**
         * Initial shape of the fine surface: elevation (m) at a point (m, world coordinates; column x
         * spans {@code [x·L, (x+1)·L)}). {@code null} interpolates between column centres.
         */
        public Builder detailRelief(java.util.function.DoubleBinaryOperator relief) { this.detailRelief = relief; return this; }

        public VolcanoSystem build() {
            return new VolcanoSystem(this);
        }
    }
}
