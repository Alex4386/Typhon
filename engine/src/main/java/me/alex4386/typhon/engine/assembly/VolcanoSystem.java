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
    private VolcanoScaling scaling;
    private final MagmaChamber chamber;
    /** Every chamber by id, the main one included (in id order). */
    private final java.util.Map<String, MagmaChamber> chambers;
    /** Magma moving between chambers; {@code null} for a single-chamber volcano. */
    private final me.alex4386.typhon.engine.magma.plumbing.MagmaTransfer plumbing;
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

    private double ballisticFraction;

    private VolcanoSystem(Builder b) {
        this.volcanoId = b.volcanoId;
        this.ballisticFraction = b.ballisticFraction;
        this.vents = b.vents;
        this.scaling = b.scaling;
        b.lava.setMetersPerBlock(scaling.metersPerBlock());
        b.terrain.setMetersPerBlock(scaling.metersPerBlock());

        BlockPos primary = vents.get(0).position();
        Derived d = derive(b);
        MagmaChamberConfig chamberConfig = d.chamber();
        this.chamber = new MagmaChamber(chamberConfig);
        java.util.Map<String, MagmaChamber> all = new java.util.TreeMap<>();
        all.put(MagmaChamberConfig.MAIN, chamber);
        for (MagmaChamberConfig extra : d.extraChambers()) {
            MagmaChamber c = new MagmaChamber(extra);
            c.setEruptive(false); // only the main chamber feeds the summit conduit; deeper ones feed it
            all.put(extra.chamberId(), c);
        }
        this.chambers = java.util.Collections.unmodifiableMap(all);
        this.plumbing = all.size() > 1 || !d.connections().isEmpty()
                ? new me.alex4386.typhon.engine.magma.plumbing.MagmaTransfer(volcanoId, all, chamber, d.connections(), scaling.metersPerBlock())
                : null;
        // The lava this volcano erupts lives on the volcano's clock: emplaced at the eruptive time
        // compression, cooling on afterwards at the dormant one. Read live, so a retuned time
        // compression applies at once.
        MagmaChamber clockChamber = chamber;
        java.util.function.DoubleSupplier clock = () -> clockChamber.erupting() ? clockChamber.config().eruptiveTimeScale()
                : clockChamber.config().dormantTimeScale();
        b.lava.registerClock(volcanoId, clock);

        this.seismicity = new SeismicityModel(d.seismic(), chamber);
        this.alert = new AlertLevelEstimator(d.alert(), chamber, seismicity);

        if (b.dikes) {
            this.dikes = new DikePropagation(d.dike(), DikeMagmaSource.of(chamber), b.terrain);
            dikes.setHypocenterListener(seismicity::queueInducedVt);
        } else {
            this.dikes = null;
        }

        this.tephra = new TephraSubsystem("tephra:" + volcanoId, b.terrain, d.tephra());

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
            this.geothermal = new Geothermal(volcanoId, d.geothermal(), d.geothermalCenter(), chamber, b.terrain, b.palette,
                    vents, subsurface);
            subsurface.setHeatSources(volcanoId, geothermal);
        } else {
            this.geothermal = null;
        }

        if (b.massFlows) {
            this.pdc = new PyroclasticFlows(PyroclasticFlows.defaultId(volcanoId), b.terrain, d.pdc());
            this.lahars = new Lahars(Lahars.defaultId(volcanoId), b.terrain, d.lahar());
            // loose ignimbrite and tephra in the world model are lahar source material automatically
            this.avalanches = b.geomorphology
                    ? new DebrisAvalanches(DebrisAvalanches.defaultId(volcanoId), b.terrain, d.avalanche()) : null;
        } else {
            this.pdc = null;
            this.lahars = null;
            this.avalanches = null;
        }

        if (b.deformation) {
            this.deformation = new DeformationModel(d.deformation(), chamber,
                    dikes != null ? dikes::geometries : List::of, b.terrain);
        } else {
            this.deformation = null;
        }

        if (deformation != null && chambers.size() > 1) {
            List<MagmaChamber> extras = chambers.values().stream().filter(c -> c != chamber).toList();
            double shear = d.deformation().shearModulusPa;
            deformation.setExtraSources(() -> extras.stream()
                    .map(c -> new DeformationModel.Source(
                            me.alex4386.typhon.engine.deformation.Mogi.volumeChange(c.volumeM3() - c.wallGrowthM3(), c.overpressureMPa(), shear)
                                    + c.inelasticVolumeChangeM3(),
                            c.config().lithostaticDepth(), c.config().center().x(), c.config().center().z()))
                    .toList());
        }
        // loose deposits of every kind (tephra, bombs, wet tephra, debris) stand at most at their angle of repose
        if (b.terrain != null) b.terrain.world().enableReposeRelaxation();
        this.coupler = new VolcanoCoupler(volcanoId, chamber, seismicity, vents, dikes, b.terrain, b.lava, tephra, pdc,
                geothermal, scaling, b.ballisticFraction);

        if (subsurface != null) coupler.setGround(subsurface);
        this.classifier = new EruptionClassifier(volcanoId, chamber, coupler);
        alert.setClassifier(classifier);

        // Every deposit is attributed to this volcano's current eruption (stratigraphy).
        this.units = new VolcanoUnits(b.terrain.world(), volcanoId, chamber);
        me.alex4386.typhon.engine.world.SurfaceDetailConfig detailConfig = d.detail();
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
            this.geomorphology = new Geomorphology(Geomorphology.defaultId(volcanoId), volcanoId, b.terrain, d.geomorph());
            geomorphology.setUnits(units);
            geomorphology.setGround(GroundState.of(subsurface));
            geomorphology.setFlows(avalanches, lahars, pdc);
            geomorphology.setChamber(ChamberRoof.of(chamber));
            geomorphology.setTimeScale(clock);
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
     * Every configuration this volcano's subsystems are built with, derived from a builder's settings
     * and the volcano's scaling. Pure: it touches no shared world objects, so a live retune can derive
     * the configurations of a changed definition and hand them to the running subsystems.
     */
    record Derived(MagmaChamberConfig chamber, List<MagmaChamberConfig> extraChambers,
            List<me.alex4386.typhon.engine.magma.plumbing.ConnectionConfig> connections, SeismicConfig seismic, AlertConfig alert, DikeConfig dike,
            TephraConfig tephra, GeothermalConfig geothermal, BlockPos geothermalCenter, MassFlowConfig pdc,
            MassFlowConfig lahar, MassFlowConfig avalanche, DeformationConfig deformation, GeomorphConfig geomorph,
            me.alex4386.typhon.engine.world.SurfaceDetailConfig detail) {}

    static Derived derive(Builder b) {
        VolcanoScaling scaling = b.scaling;
        String volcanoId = b.volcanoId;
        BlockPos primary = b.vents.get(0).position();
        MagmaChamberConfig chamberConfig = (b.chamberConfig != null
                        ? b.chamberConfig.toBuilder()
                        : MagmaChamberConfig.builder(volcanoId, defaultChamberCenter(primary)))
                .dormantTimeScale(scaling.dormantTimeCompression())
                .eruptiveTimeScale(scaling.eruptiveTimeCompression())
                .build();
        if (!chamberConfig.volcanoId().equals(volcanoId)) {
            throw new IllegalArgumentException("Chamber config is for volcano " + chamberConfig.volcanoId());
        }
        if (!chamberConfig.isMain()) throw new IllegalArgumentException("The main chamber's id must be " + MagmaChamberConfig.MAIN);
        List<MagmaChamberConfig> extraChambers = new java.util.ArrayList<>();
        for (MagmaChamberConfig extra : b.plumbing.chambers()) {
            if (!extra.volcanoId().equals(volcanoId)) throw new IllegalArgumentException("Chamber config is for volcano " + extra.volcanoId());
            // one clock for the whole plumbing: the volcano's time scales
            extraChambers.add(extra.toBuilder().dormantTimeScale(scaling.dormantTimeCompression())
                    .eruptiveTimeScale(scaling.eruptiveTimeCompression()).build());
        }
        extraChambers.sort(java.util.Comparator.comparing(MagmaChamberConfig::chamberId));
        double failure = chamberConfig.tensileStrengthMPa();
        SeismicConfig seismic = SeismicConfig.builder(volcanoId, primary).failureOverpressureMPa(failure).build();
        AlertConfig alert = AlertConfig.defaults(volcanoId).withFailureOverpressure(failure);

        DikeConfig dike = null;
        if (b.dikes) {
            dike = (b.dikeConfig != null ? b.dikeConfig : DikeConfig.defaults()).withScaling(scaling);
            dike.timeScale = scaling.eruptiveTimeCompression();
        }

        TephraConfig tephra = b.tephraConfig != null ? b.tephraConfig.copy() : new TephraConfig();
        tephra.ballisticSpeedScale = scaling.velocityScale();
        tephra.plumeHeightScale = scaling.plumeHeightScale();
        tephra.massScale = scaling.volumeScale();
        if (b.windSet) {
            tephra.initialWindSpeed = b.windSpeed * scaling.velocityScale();
            tephra.initialWindDirectionRad = b.windBearing;
            tephra.initialWindVariability = b.windVariability;
        }

        GeothermalConfig geothermal = null;
        BlockPos geothermalCenter = null;
        if (b.geothermal) {
            geothermal = b.geothermalConfig != null ? b.geothermalConfig : new GeothermalConfig();
            if (b.geothermalPrewarmSeconds >= 0) geothermal.prewarmSeconds = b.geothermalPrewarmSeconds;
            BlockPos chamberCenter = chamberConfig.center();
            geothermalCenter = b.geothermalCenter != null
                    ? b.geothermalCenter
                    : new BlockPos(chamberCenter.x(), primary.y(), chamberCenter.z());
        }

        MassFlowConfig pdc = null;
        MassFlowConfig lahar = null;
        MassFlowConfig avalanche = null;
        if (b.massFlows) {
            pdc = b.pdcConfig != null ? b.pdcConfig.copy() : MassFlowConfig.pdc();
            pdc.metersPerBlock = scaling.metersPerBlock();
            lahar = b.laharConfig != null ? b.laharConfig.copy() : MassFlowConfig.lahar();
            lahar.metersPerBlock = scaling.metersPerBlock();
            if (b.geomorphology) {
                avalanche = b.avalancheConfig != null ? b.avalancheConfig.copy() : MassFlowConfig.debrisAvalanche();
                avalanche.metersPerBlock = scaling.metersPerBlock();
            }
        }

        DeformationConfig deformation = null;
        if (b.deformation) {
            deformation = DeformationConfig.forChamber(chamberConfig, scaling);
            deformation.stations = new java.util.ArrayList<>(b.stations);
        }

        GeomorphConfig geomorph = b.geomorphology ? (b.geomorphConfig != null ? b.geomorphConfig : new GeomorphConfig()) : null;
        me.alex4386.typhon.engine.world.SurfaceDetailConfig detail = b.detailConfig != null ? b.detailConfig
                : me.alex4386.typhon.engine.world.SurfaceDetailConfig.defaults(scaling.metersPerBlock(),
                        b.vents.get(0).craterRadius() * scaling.metersPerBlock());
        return new Derived(chamberConfig, List.copyOf(extraChambers), b.plumbing.connections(), seismic, alert, dike, tephra, geothermal, geothermalCenter, pdc, lahar,
                avalanche, deformation, geomorph, detail);
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
        for (MagmaChamber c : chambers.values()) if (c != chamber) list.add(c);
        if (plumbing != null) list.add(plumbing);
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
    /** Every chamber of the plumbing by id (the main, eruptive one under {@link MagmaChamberConfig#MAIN}). */
    public java.util.Map<String, MagmaChamber> chambers() { return chambers; }
    /** Magma moving between chambers; {@code null} for a single-chamber volcano. */
    public me.alex4386.typhon.engine.magma.plumbing.MagmaTransfer plumbing() { return plumbing; }
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
        private me.alex4386.typhon.engine.magma.plumbing.PlumbingConfig plumbing = me.alex4386.typhon.engine.magma.plumbing.PlumbingConfig.NONE;
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
        /** Further chambers and the pathways between them (none: a single-chamber volcano). */
        public Builder plumbing(me.alex4386.typhon.engine.magma.plumbing.PlumbingConfig plumbing) {
            this.plumbing = Objects.requireNonNull(plumbing);
            return this;
        }
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

        /**
         * The configuration each subsystem of the volcano {@link #build} would assemble has, by subsystem
         * id, without building anything (no shared world object is touched). Live retuning hands these to
         * the running subsystems ({@link VolcanoSystem#reconfigure}).
         */
        public java.util.Map<String, Object> subsystemConfigs() {
            Derived d = derive(this);
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("magma:" + volcanoId, d.chamber());
            for (MagmaChamberConfig extra : d.extraChambers()) out.put(MagmaChamber.id(volcanoId, extra.chamberId()), extra);
            if (!d.extraChambers().isEmpty() || !d.connections().isEmpty()) {
                out.put(me.alex4386.typhon.engine.magma.plumbing.MagmaTransfer.defaultId(volcanoId), d.connections());
            }
            if (d.dike() != null) out.put("dike:" + volcanoId, d.dike());
            out.put("seismic:" + volcanoId, d.seismic());
            out.put("alert:" + volcanoId, d.alert());
            out.put("tephra:" + volcanoId, d.tephra());
            if (d.pdc() != null) {
                out.put(PyroclasticFlows.defaultId(volcanoId), d.pdc());
                out.put(Lahars.defaultId(volcanoId), d.lahar());
            }
            if (d.geomorph() != null) out.put(Geomorphology.defaultId(volcanoId), d.geomorph());
            if (d.avalanche() != null) out.put(DebrisAvalanches.defaultId(volcanoId), d.avalanche());
            if (d.geothermal() != null) out.put("geothermal:" + volcanoId, d.geothermal());
            if (d.deformation() != null) out.put("deformation:" + volcanoId, d.deformation());
            if (d.detail().enabled()) out.put("detail:" + volcanoId, d.detail());
            return out;
        }
    }

    /**
     * Retunes the running volcano to a changed definition's configurations ({@link Builder#subsystemConfigs}),
     * in place and keeping all state: each subsystem whose configuration differs takes the new one
     * (see {@link Engine#reconfigure}). Call on the engine thread between steps. Fails, changing
     * nothing it could avoid, if a subsystem cannot take its change in place.
     */
    public void reconfigure(Engine engine, Builder changed) {
        java.util.Map<String, Object> configs = changed.subsystemConfigs();
        for (java.util.Map.Entry<String, Object> e : configs.entrySet()) {
            if (!engine.hasSubsystem(e.getKey())) {
                throw new IllegalArgumentException("Subsystem " + e.getKey() + " is not running; the volcano must be rebuilt");
            }
            if (Engine.configHash(e.getValue()).equals(engine.configHash(e.getKey()))) continue;
            engine.reconfigure(e.getKey(), e.getValue());
        }
        this.ballisticFraction = changed.ballisticFraction;
        this.scaling = changed.scaling;
        coupler.setScaling(changed.scaling, changed.ballisticFraction);
    }
}
