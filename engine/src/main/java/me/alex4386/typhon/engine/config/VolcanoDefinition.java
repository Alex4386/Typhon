package me.alex4386.typhon.engine.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import me.alex4386.typhon.engine.deformation.GeodeticStation;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.dike.DikeConfig;
import me.alex4386.typhon.engine.geothermal.GeothermalConfig;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.ConduitConfig;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.massflow.MassFlowConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.tephra.TephraConfig;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.Edifice;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.SurfaceDetailConfig;

/**
 * The parsed {@code volcanoes/<id>.yaml}: one volcano's vents, magma system and per-subsystem
 * parameters. Sections map onto the engine's configuration objects key by key (see
 * {@link ConfigBinder}), so any tunable of {@code MagmaChamberConfig}, {@code ConduitConfig},
 * {@code DikeConfig}, {@code GeothermalConfig}, {@code MassFlowConfig} and {@code TephraConfig} can be
 * set; values derived from the world scaling cannot.
 *
 * <pre>{@code
 * name: Kīlauea
 * active: true
 * vents:
 *   - {id: halemaumau, kind: crater, x: 0, y: 70, z: 0, radius: 6}
 *   - {id: east-rift, kind: fissure, x: 40, y: 52, z: 12, angleDeg: 30, length: 24}
 * timeCompression: {dormant: 5000, eruptive: 20}    # optional, overrides world scaling
 * ballisticFraction: 0.05
 * magma:
 *   chamber: {center: {x: 0, y: -40, z: 0}, volume: 1.0e10, supplyRate: 3, initialSilicaWt: 50}
 *   conduit: {initialOpenness: 1}
 * dikes: {enabled: true, conduitSealing: 0.5}
 * geothermal: {enabled: true, maxGeysers: 6}
 * massFlows: {enabled: true, pdc: {frictionCoefficient: 0.18}, lahar: {}}
 * deformation: {enabled: true}
 * tephra: {bombMedianDiameter: 0.3}
 * edifice: {material: basalt, radius: 400, baseZ: 200}   # radius in columns, baseZ in metres
 * detail: {radiusM: 300, metersPerCell: 1}   # crater-resolving fine surface; {enabled: false} turns it off
 * }</pre>
 *
 * Inactive volcanoes are built with no magma supply (dormant); their state is kept.
 *
 * @param dormantCompression {@code NaN} = world scaling
 * @param eruptiveCompression {@code NaN} = world scaling
 * @param dikes {@code null} when disabled
 * @param geothermal {@code null} when disabled
 * @param geothermalCenter {@code null} = above the chamber
 * @param pdc {@code null} when mass flows are disabled
 * @param lahar {@code null} when mass flows are disabled
 * @param edificeMaterial material of this volcano's edifice for imported columns, {@code null} = world
 * @param edificeRadius radius of the edifice around the primary vent in columns, {@code +∞} = everywhere
 *     this volcano is the nearest one
 * @param edificeBaseZ elevation of the edifice's base, the pre-volcano surface (m); {@code NaN} = the
 *     top of the world's basement cake
 * @param stations virtual GNSS/tilt stations of the deformation model (world columns)
 * @param detail crater-resolving fine surface around the primary vent ({@code detail:}), {@code null}
 *     = sized from the crater ({@link SurfaceDetailConfig#defaults})
 */
public record VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
        MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, BlockPos geothermalCenter,
        MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double dormantCompression,
        double eruptiveCompression, double ballisticFraction, String edificeMaterial, double edificeRadius,
        double edificeBaseZ, List<GeodeticStation> stations, SurfaceDetailConfig detail) {

    static final Set<String> CHAMBER_DERIVED = Set.of("dormantTimeScale", "eruptiveTimeScale");
    static final Set<String> CHAMBER_SKIP = Set.of("volcanoId", "center", "conduit", "dormantTimeScale", "eruptiveTimeScale");
    static final Set<String> DIKE_DERIVED = Set.of("metersPerBlock", "timeScale");
    static final Set<String> MASSFLOW_DERIVED = Set.of("metersPerBlock");
    static final Set<String> TEPHRA_DERIVED = Set.of("ballisticSpeedScale", "plumeHeightScale", "massScale");

    public VolcanoDefinition {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z0-9_-]+")) throw new ConfigException("volcano id '" + id + "' must match [a-z0-9_-]+");
        vents = List.copyOf(vents);
        if (vents.isEmpty()) throw new ConfigException("volcano " + id + " needs at least one vent");
        Objects.requireNonNull(chamber, "chamber");
        Objects.requireNonNull(tephra, "tephra");
        if (!(edificeRadius > 0)) throw new ConfigException("volcano " + id + ": edifice radius must be > 0");
        stations = List.copyOf(stations);
    }

    /** Definition with the default fine surface. */
    public VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
            MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, BlockPos geothermalCenter,
            MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double dormantCompression,
            double eruptiveCompression, double ballisticFraction, String edificeMaterial, double edificeRadius,
            double edificeBaseZ, List<GeodeticStation> stations) {
        this(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar, deformation, tephra,
                dormantCompression, eruptiveCompression, ballisticFraction, edificeMaterial, edificeRadius, edificeBaseZ,
                stations, null);
    }

    /** Definition without deformation stations. */
    public VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
            MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, BlockPos geothermalCenter,
            MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double dormantCompression,
            double eruptiveCompression, double ballisticFraction, String edificeMaterial, double edificeRadius,
            double edificeBaseZ) {
        this(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar, deformation, tephra,
                dormantCompression, eruptiveCompression, ballisticFraction, edificeMaterial, edificeRadius, edificeBaseZ,
                List.of());
    }

    /** Definition with an unbounded edifice at the world's basement top (no edifice radius/base). */
    public VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
            MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, BlockPos geothermalCenter,
            MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double dormantCompression,
            double eruptiveCompression, double ballisticFraction, String edificeMaterial) {
        this(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar, deformation, tephra,
                dormantCompression, eruptiveCompression, ballisticFraction, edificeMaterial, Double.POSITIVE_INFINITY,
                Double.NaN);
    }

    /** This volcano's edifice zone for importing columns, or {@code null} if it uses the world's rock. */
    public Edifice edifice() {
        if (edificeMaterial == null) return null;
        BlockPos c = primaryVent().position();
        return new Edifice(id, c.x() + 0.5, c.z() + 0.5, edificeRadius, edificeBaseZ, edificeMaterial);
    }

    public VentSite primaryVent() {
        return vents.get(0);
    }

    // ── Parsing ──

    /** Parses a volcano file; {@code id} comes from the file name ({@code volcanoes/<id>.yaml}). */
    public static VolcanoDefinition parse(String id, ConfigNode root) {
        String declared = root.string("id", id);
        if (!declared.equals(id)) throw root.error("id", "'" + declared + "' does not match the file name '" + id + "'");
        String name = root.string("name", id);
        boolean active = root.bool("active", true);

        List<VentSite> vents = new ArrayList<>();
        for (ConfigNode v : root.children("vents")) vents.add(parseVent(v));
        if (vents.isEmpty()) throw root.error("vents", "at least one vent is required");
        for (int i = 0; i < vents.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (vents.get(i).id().equals(vents.get(j).id())) {
                    throw root.error("vents", "duplicate vent id '" + vents.get(i).id() + "'");
                }
            }
        }

        double dormant = Double.NaN;
        double eruptive = Double.NaN;
        if (root.has("timeCompression")) {
            ConfigNode tc = root.child("timeCompression");
            dormant = tc.number("dormant", Double.NaN);
            eruptive = tc.number("eruptive", Double.NaN);
            tc.finish();
            if (dormant <= 0 || eruptive <= 0) throw tc.error("compressions must be > 0");
        }
        double ballistic = root.number("ballisticFraction", 0.05);
        if (!(ballistic >= 0 && ballistic <= 1)) throw root.error("ballisticFraction", "must be in [0, 1]");

        ConfigNode magma = root.child("magma");
        ConfigNode chamberNode = magma.child("chamber");
        BlockPos center = chamberNode.has("center")
                ? parsePos(chamberNode.child("center"))
                : VolcanoSystem.defaultChamberCenter(vents.get(0).position());
        chamberNode.markUsed("center");
        ConduitConfig conduit = ConfigBinder.bindRecord(magma.child("conduit"), ConduitConfig.DEFAULT, Set.of(), Set.of());
        MagmaChamberConfig.Builder builder = MagmaChamberConfig.builder(id, center).conduit(conduit);
        ConfigBinder.bindBuilder(chamberNode, builder, Set.of("center"), CHAMBER_DERIVED);
        MagmaChamberConfig chamber;
        try {
            chamber = builder.build();
        } catch (IllegalArgumentException e) {
            throw chamberNode.error(e.getMessage());
        }
        magma.finish();

        DikeConfig dikes = null;
        ConfigNode dikeNode = root.child("dikes");
        if (dikeNode.bool("enabled", true)) {
            dikes = DikeConfig.defaults();
            ConfigBinder.bindFields(dikeNode, dikes, Set.of("enabled"), DIKE_DERIVED);
        } else {
            dikeNode.asMap();
        }

        GeothermalConfig geothermal = null;
        BlockPos geothermalCenter = null;
        ConfigNode geoNode = root.child("geothermal");
        if (geoNode.bool("enabled", true)) {
            geothermal = new GeothermalConfig();
            if (geoNode.has("center")) geothermalCenter = parsePos(geoNode.child("center"));
            ConfigBinder.bindFields(geoNode, geothermal, Set.of("enabled", "center"), Set.of());
            try {
                geothermal.validate();
            } catch (IllegalArgumentException e) {
                throw geoNode.error(e.getMessage());
            }
        } else {
            geoNode.asMap();
        }

        MassFlowConfig pdc = null;
        MassFlowConfig lahar = null;
        ConfigNode flows = root.child("massFlows");
        if (flows.bool("enabled", true)) {
            pdc = MassFlowConfig.pdc();
            ConfigBinder.bindFields(flows.child("pdc"), pdc, Set.of(), MASSFLOW_DERIVED);
            lahar = MassFlowConfig.lahar();
            ConfigBinder.bindFields(flows.child("lahar"), lahar, Set.of(), MASSFLOW_DERIVED);
            flows.finish();
        } else {
            flows.asMap();
        }

        ConfigNode deformationNode = root.child("deformation");
        boolean deformation = deformationNode.bool("enabled", true);
        List<GeodeticStation> stations = new ArrayList<>();
        for (ConfigNode st : deformationNode.children("stations")) {
            stations.add(new GeodeticStation(st.requireString("name"), st.integer("x", 0), st.integer("z", 0)));
            st.finish();
        }
        deformationNode.finish();

        TephraConfig tephra = new TephraConfig();
        ConfigBinder.bindFields(root.child("tephra"), tephra, Set.of(), TEPHRA_DERIVED);

        String edifice = null;
        double edificeRadius = Double.POSITIVE_INFINITY;
        double edificeBase = Double.NaN;
        if (root.has("edifice")) {
            ConfigNode e = root.child("edifice");
            edifice = e.requireString("material");
            if (MaterialTable.byName(edifice) == null) {
                throw e.error("material", "unknown material '" + edifice + "'; known: "
                        + MaterialTable.all().stream().map(m -> m.name()).toList());
            }
            edificeRadius = e.number("radius", Double.POSITIVE_INFINITY);
            if (!(edificeRadius > 0)) throw e.error("radius", "must be > 0 (columns)");
            edificeBase = e.number("baseZ", Double.NaN);
            e.finish();
        }

        SurfaceDetailConfig detail = null;
        if (root.has("detail")) {
            ConfigNode d = root.child("detail");
            try {
                detail = d.bool("enabled", true)
                        ? new SurfaceDetailConfig(d.requireNumber("radiusM"), d.requireNumber("metersPerCell"))
                        : SurfaceDetailConfig.DISABLED;
            } catch (IllegalArgumentException e) {
                throw d.error(e.getMessage());
            }
            d.finish();
        }

        root.finish();
        return new VolcanoDefinition(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar,
                deformation, tephra, dormant, eruptive, ballistic, edifice, edificeRadius, edificeBase, stations, detail);
    }

    static VentSite parseVent(ConfigNode v) {
        String ventId = v.requireString("id");
        String kind = v.string("kind", "crater");
        BlockPos position = new BlockPos(v.integer("x", 0), v.integer("y", 0), v.integer("z", 0));
        VentSite vent;
        try {
            vent = switch (kind) {
                case "crater" -> VentSite.crater(ventId, position, v.integer("radius", 4));
                case "fissure" -> new VentSite(ventId, position, VentKind.FISSURE, v.integer("radius", 1),
                        Math.toRadians(v.number("angleDeg", 0)), v.integer("length", 10));
                default -> throw v.error("kind", "expected crater or fissure, got '" + kind + "'");
            };
        } catch (IllegalArgumentException e) {
            throw v.error(e.getMessage());
        }
        v.finish();
        return vent;
    }

    static BlockPos parsePos(ConfigNode p) {
        BlockPos pos = new BlockPos(p.integer("x", 0), p.integer("y", 0), p.integer("z", 0));
        p.finish();
        return pos;
    }

    // ── Building ──

    /** Scaling for this volcano: the world's, with this volcano's time compressions if set. */
    public VolcanoScaling scaling(VolcanoScaling world) {
        return world.withTimeCompression(
                Double.isNaN(dormantCompression) ? world.dormantTimeCompression() : dormantCompression,
                Double.isNaN(eruptiveCompression) ? world.eruptiveTimeCompression() : eruptiveCompression);
    }

    /**
     * Assembles the volcano's subsystems on the shared terrain and lava field. Configuration objects
     * are copied, so a definition can be assembled again (engine rebuilds).
     */
    public VolcanoSystem assemble(TerrainModel terrain, LavaFlow lava, WorldDefinition world) {
        return assemble(terrain, lava, world, null);
    }

    /** {@link #assemble(TerrainModel, LavaFlow, WorldDefinition)} heating the world's shared subsurface model. */
    public VolcanoSystem assemble(TerrainModel terrain, LavaFlow lava, WorldDefinition world, Subsurface subsurface) {
        MagmaChamberConfig chamberConfig = active ? chamber : chamber.toBuilder().supplyRate(0).build();
        VolcanoSystem.Builder b = VolcanoSystem.builder(id, vents, terrain, lava)
                .scaling(scaling(world.scaling()))
                .chamber(chamberConfig)
                .tephra(tephra.copy())
                .ballisticFraction(ballisticFraction)
                .dikesEnabled(dikes != null)
                .geothermalEnabled(geothermal != null)
                .massFlowsEnabled(pdc != null)
                .deformationEnabled(deformation)
                .stations(stations)
                .subsurface(subsurface)
                .detail(detail);
        if (dikes != null) b.dikes(dikes.copy());
        if (geothermal != null) b.geothermal(copyFields(geothermal, new GeothermalConfig()));
        if (geothermalCenter != null) b.geothermalCenter(geothermalCenter);
        if (pdc != null) b.pyroclasticFlows(pdc.copy()).lahars(lahar.copy());
        if (world.climate().hasWind()) {
            b.wind(world.climate().windSpeed(), Math.toRadians(world.climate().windBearingDeg()),
                    world.climate().windVariability());
        }
        return b.build();
    }

    private static <T> T copyFields(T source, T target) {
        ConfigNode node = ConfigNode.root("copy", ConfigBinder.exportFields(source, Set.of()));
        ConfigBinder.bindFields(node, target, Set.of(), Set.of());
        return target;
    }

    // ── Export ──

    /**
     * Definition of an assembled volcano, e.g. to write a world template from a built-in scenario.
     * Values the scaling derives are left out; time compressions are recorded only where they differ
     * from {@code world}.
     */
    public static VolcanoDefinition fromSystem(VolcanoSystem system, VolcanoScaling world) {
        MagmaChamberConfig chamber = system.chamber().config();
        VolcanoScaling own = system.scaling();
        return new VolcanoDefinition(
                system.volcanoId(), system.volcanoId(), true, system.vents(), chamber,
                system.dikes() != null ? system.dikes().config().copy() : null,
                system.geothermal() != null
                        ? copyFields((GeothermalConfig) system.geothermal().config(), new GeothermalConfig()) : null,
                system.geothermal() != null ? system.geothermal().center() : null,
                system.pyroclasticFlows() != null ? system.pyroclasticFlows().config().copy() : null,
                system.lahars() != null ? system.lahars().config().copy() : null,
                system.deformation() != null,
                system.tephra().config().copy(),
                own.dormantTimeCompression() == world.dormantTimeCompression() ? Double.NaN : own.dormantTimeCompression(),
                own.eruptiveTimeCompression() == world.eruptiveTimeCompression() ? Double.NaN : own.eruptiveTimeCompression(),
                system.ballisticFraction(),
                null, Double.POSITIVE_INFINITY, Double.NaN,
                system.deformation() != null ? system.deformation().config().stations : List.of(),
                explicitDetail(system));
    }

    /** The system's fine-surface setting, or {@code null} when it is the crater-sized default. */
    private static SurfaceDetailConfig explicitDetail(VolcanoSystem system) {
        SurfaceDetailConfig own = system.surfaceDetailConfig();
        SurfaceDetailConfig derived = SurfaceDetailConfig.defaults(system.scaling().metersPerBlock(),
                system.vents().get(0).craterRadius() * system.scaling().metersPerBlock());
        return own.equals(derived) ? null : own;
    }

    /** The effective definition as a YAML tree (defaults included); also the basis of its hash. */
    public Map<String, Object> toTree() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", id);
        root.put("name", name);
        root.put("active", active);
        List<Object> ventList = new ArrayList<>();
        for (VentSite v : vents) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.id());
            m.put("kind", v.kind() == VentKind.CRATER ? "crater" : "fissure");
            m.put("x", v.position().x());
            m.put("y", v.position().y());
            m.put("z", v.position().z());
            m.put("radius", v.craterRadius());
            if (v.kind() == VentKind.FISSURE) {
                m.put("angleDeg", Math.toDegrees(v.fissureAngleRad()));
                m.put("length", v.fissureLength());
            }
            ventList.add(m);
        }
        root.put("vents", ventList);
        if (!Double.isNaN(dormantCompression) || !Double.isNaN(eruptiveCompression)) {
            Map<String, Object> tc = new LinkedHashMap<>();
            if (!Double.isNaN(dormantCompression)) tc.put("dormant", dormantCompression);
            if (!Double.isNaN(eruptiveCompression)) tc.put("eruptive", eruptiveCompression);
            root.put("timeCompression", tc);
        }
        root.put("ballisticFraction", ballisticFraction);

        Map<String, Object> chamberTree = new LinkedHashMap<>();
        BlockPos c = chamber.center();
        chamberTree.put("center", WorldDefinition.map("x", c.x(), "y", c.y(), "z", c.z()));
        chamberTree.putAll(ConfigBinder.exportRecord(chamber, CHAMBER_SKIP));
        Map<String, Object> magma = new LinkedHashMap<>();
        magma.put("chamber", chamberTree);
        magma.put("conduit", ConfigBinder.exportRecord(chamber.conduit(), Set.of()));
        root.put("magma", magma);

        Map<String, Object> dikeTree = new LinkedHashMap<>();
        dikeTree.put("enabled", dikes != null);
        if (dikes != null) dikeTree.putAll(ConfigBinder.exportFields(dikes, DIKE_DERIVED));
        root.put("dikes", dikeTree);

        Map<String, Object> geoTree = new LinkedHashMap<>();
        geoTree.put("enabled", geothermal != null);
        if (geothermal != null) {
            if (geothermalCenter != null) {
                geoTree.put("center", WorldDefinition.map("x", geothermalCenter.x(), "y", geothermalCenter.y(),
                        "z", geothermalCenter.z()));
            }
            geoTree.putAll(ConfigBinder.exportFields(geothermal, Set.of()));
        }
        root.put("geothermal", geoTree);

        Map<String, Object> flows = new LinkedHashMap<>();
        flows.put("enabled", pdc != null);
        if (pdc != null) {
            flows.put("pdc", ConfigBinder.exportFields(pdc, MASSFLOW_DERIVED));
            flows.put("lahar", ConfigBinder.exportFields(lahar, MASSFLOW_DERIVED));
        }
        root.put("massFlows", flows);
        Map<String, Object> deformationTree = WorldDefinition.map("enabled", deformation);
        if (!stations.isEmpty()) {
            List<Object> stationList = new ArrayList<>();
            for (GeodeticStation st : stations) stationList.add(WorldDefinition.map("name", st.name(), "x", st.x(), "z", st.z()));
            deformationTree.put("stations", stationList);
        }
        root.put("deformation", deformationTree);
        root.put("tephra", ConfigBinder.exportFields(tephra, TEPHRA_DERIVED));
        if (edificeMaterial != null) {
            Map<String, Object> edifice = WorldDefinition.map("material", edificeMaterial);
            if (!Double.isInfinite(edificeRadius)) edifice.put("radius", ConfigBinder.export(edificeRadius));
            if (!Double.isNaN(edificeBaseZ)) edifice.put("baseZ", ConfigBinder.export(edificeBaseZ));
            root.put("edifice", edifice);
        }
        if (detail != null) {
            root.put("detail", detail.enabled()
                    ? WorldDefinition.map("radiusM", detail.radiusM(), "metersPerCell", detail.metersPerCell())
                    : WorldDefinition.map("enabled", false));
        }
        return root;
    }

    /** Same definition with an edifice zone (material, radius in columns, base elevation in metres). */
    public VolcanoDefinition withEdifice(String material, double radius, double baseZ) {
        return new VolcanoDefinition(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar,
                deformation, tephra, dormantCompression, eruptiveCompression, ballisticFraction, material, radius, baseZ,
                stations, detail);
    }

    /** Same definition with a different {@code active} flag. */
    public VolcanoDefinition withActive(boolean value) {
        return new VolcanoDefinition(id, name, value, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar,
                deformation, tephra, dormantCompression, eruptiveCompression, ballisticFraction, edificeMaterial,
                edificeRadius, edificeBaseZ, stations, detail);
    }
}
