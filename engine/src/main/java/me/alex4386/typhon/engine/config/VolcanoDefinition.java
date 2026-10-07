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
import me.alex4386.typhon.engine.magma.plumbing.ConnectionConfig;
import me.alex4386.typhon.engine.magma.plumbing.PlumbingConfig;
import me.alex4386.typhon.engine.massflow.MassFlowConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
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
 *   - {id: halemaumau, kind: crater, x: 2, y: -2, elevation: 1136, radius: 6}
 *   - {id: east-rift, kind: fissure, x: 640, y: -192, elevation: 848, angleDeg: 30, length: 24}
 * ballisticFraction: 0.05
 * magma:
 *   chamber: {center: {x: 2, y: -2, elevation: -632}, volume: 1.0e10, supplyRate: 3, initialSilicaWt: 50}
 *   conduit: {initialOpenness: 1}
 * dikes: {enabled: true, blocked: false}
 * geothermal: {enabled: true, maxGeysers: 6}
 * massFlows: {enabled: true, pdc: {frictionCoefficient: 0.18}, lahar: {}}
 * deformation: {enabled: true}
 * tephra: {bombMedianDiameter: 0.3}
 * edifice: {material: basalt, radius: 400, baseZ: 200}   # radius in columns, baseZ in metres
 * detail: {radiusM: 300, metersPerCell: 1}   # crater-resolving fine surface; {enabled: false} turns it off
 * }</pre>
 *
 * Positions are metres on the map ({@code x} east, {@code y} north, {@code elevation} up): a vent's is its
 * surface point, a chamber's its centre. Radii and lengths stay in columns. Files from before metre
 * positions give {@code {x, y, z}} block coordinates (engine axes: {@code y} up, {@code z} south); they
 * load through a migration (with a warning) and are written back in metres.
 *
 * Inactive volcanoes are built with no magma supply (dormant); their state is kept.
 *
 * @param dikes {@code null} when disabled
 * @param geothermal {@code null} when disabled
 * @param geothermalCenter centre of the hot-spring field on the ground (m), {@code null} = above the chamber
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
 * @param plumbing further magma chambers and the pathways between them ({@code magma.chambers},
 *     {@code magma.connections}); {@link PlumbingConfig#NONE} for one chamber
 */
public record VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
        MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, Point3 geothermalCenter,
        MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double ballisticFraction, String edificeMaterial, double edificeRadius,
        double edificeBaseZ, List<GeodeticStation> stations, SurfaceDetailConfig detail, PlumbingConfig plumbing) {

    static final Set<String> CHAMBER_DERIVED = Set.of();
    static final Set<String> CHAMBER_SKIP = Set.of("volcanoId", "center", "conduit", "chamberId");
    static final Set<String> DIKE_DERIVED = Set.of("metersPerBlock");
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
        if (plumbing == null) plumbing = PlumbingConfig.NONE;
    }

    /** Definition with a single-chamber plumbing. */
    public VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
            MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, Point3 geothermalCenter,
            MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double ballisticFraction, String edificeMaterial, double edificeRadius,
            double edificeBaseZ, List<GeodeticStation> stations, SurfaceDetailConfig detail) {
        this(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar, deformation, tephra,
                ballisticFraction, edificeMaterial, edificeRadius, edificeBaseZ,
                stations, detail, PlumbingConfig.NONE);
    }

    /** Definition with the default fine surface. */
    public VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
            MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, Point3 geothermalCenter,
            MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double ballisticFraction, String edificeMaterial, double edificeRadius,
            double edificeBaseZ, List<GeodeticStation> stations) {
        this(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar, deformation, tephra,
                ballisticFraction, edificeMaterial, edificeRadius, edificeBaseZ,
                stations, null);
    }

    /** Definition without deformation stations. */
    public VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
            MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, Point3 geothermalCenter,
            MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double ballisticFraction, String edificeMaterial, double edificeRadius,
            double edificeBaseZ) {
        this(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar, deformation, tephra,
                ballisticFraction, edificeMaterial, edificeRadius, edificeBaseZ,
                List.of());
    }

    /** Definition with an unbounded edifice at the world's basement top (no edifice radius/base). */
    public VolcanoDefinition(String id, String name, boolean active, List<VentSite> vents,
            MagmaChamberConfig chamber, DikeConfig dikes, GeothermalConfig geothermal, Point3 geothermalCenter,
            MassFlowConfig pdc, MassFlowConfig lahar, boolean deformation, TephraConfig tephra, double ballisticFraction, String edificeMaterial) {
        this(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar, deformation, tephra,
                ballisticFraction, edificeMaterial, Double.POSITIVE_INFINITY,
                Double.NaN);
    }

    /**
     * This volcano's edifice zone for importing columns of an {@code l}-metre grid, or {@code null} if it uses
     * the world's rock.
     */
    public Edifice edifice(double l) {
        if (edificeMaterial == null) return null;
        Point3 c = primaryVent().position();
        return new Edifice(id, c.x() / l, c.z() / l, edificeRadius, edificeBaseZ, edificeMaterial);
    }

    public VentSite primaryVent() {
        return vents.get(0);
    }

    // ── Parsing ──

    /**
     * Parses a volcano file of a world with {@code metersPerBlock} columns; {@code id} comes from the file name
     * ({@code volcanoes/<id>.yaml}). The scale places the default chamber and migrates block coordinates.
     */
    public static VolcanoDefinition parse(String id, ConfigNode root, double metersPerBlock) {
        double l = metersPerBlock;
        String declared = root.string("id", id);
        if (!declared.equals(id)) throw root.error("id", "'" + declared + "' does not match the file name '" + id + "'");
        String name = root.string("name", id);
        boolean active = root.bool("active", true);

        List<VentSite> vents = new ArrayList<>();
        for (ConfigNode v : root.children("vents")) vents.add(parseVent(v, l));
        if (vents.isEmpty()) throw root.error("vents", "at least one vent is required");
        for (int i = 0; i < vents.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (vents.get(i).id().equals(vents.get(j).id())) {
                    throw root.error("vents", "duplicate vent id '" + vents.get(i).id() + "'");
                }
            }
        }

        double ballistic = root.number("ballisticFraction", 0.05);
        if (!(ballistic >= 0 && ballistic <= 1)) throw root.error("ballisticFraction", "must be in [0, 1]");

        ConfigNode magma = root.child("magma");
        ConfigNode chamberNode = magma.child("chamber");
        Point3 center = chamberNode.has("center")
                ? parseCenter(chamberNode.child("center"), l)
                : VolcanoSystem.defaultChamberCenter(vents.get(0), l);
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
        PlumbingConfig plumbing = parsePlumbing(magma, chamber, l);
        magma.finish();

        DikeConfig dikes = null;
        ConfigNode dikeNode = root.child("dikes");
        if (dikeNode.bool("enabled", true)) {
            dikes = DikeConfig.defaults();
            ConfigBinder.bindFields(dikeNode, dikes, Set.of("enabled"), DIKE_DERIVED);
            try {
                dikes.validate();
            } catch (IllegalArgumentException e) {
                throw dikeNode.error(e.getMessage());
            }
        } else {
            dikeNode.asMap();
        }

        GeothermalConfig geothermal = null;
        Point3 geothermalCenter = null;
        ConfigNode geoNode = root.child("geothermal");
        if (geoNode.bool("enabled", true)) {
            geothermal = new GeothermalConfig();
            if (geoNode.has("center")) geothermalCenter = parseSurfacePoint(geoNode.child("center"), l);
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
            try {
                pdc.validate();
            } catch (IllegalArgumentException e) {
                throw flows.child("pdc").error(e.getMessage());
            }
            try {
                lahar.validate();
            } catch (IllegalArgumentException e) {
                throw flows.child("lahar").error(e.getMessage());
            }
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
                deformation, tephra, ballistic, edifice, edificeRadius, edificeBase, stations, detail, plumbing);
    }

    /**
     * Further chambers ({@code magma.chambers}: each {@code id}, {@code center} and any chamber setting,
     * unset ones taken from the main chamber, with no deep supply unless set) and pathways
     * ({@code magma.connections}).
     */
    static PlumbingConfig parsePlumbing(ConfigNode magma, MagmaChamberConfig main, double l) {
        List<MagmaChamberConfig> chambers = new ArrayList<>();
        for (ConfigNode n : magma.children("chambers")) {
            String chamberId = n.requireString("id");
            Point3 center = n.has("center") ? parseCenter(n.child("center"), l) : main.center();
            n.markUsed("center");
            MagmaChamberConfig.Builder b = main.toBuilder().chamberId(chamberId).center(center).supplyRate(0);
            ConfigBinder.bindBuilder(n, b, Set.of("id", "center"), CHAMBER_DERIVED);
            try {
                chambers.add(b.build());
            } catch (IllegalArgumentException e) {
                throw n.error(e.getMessage());
            }
        }
        List<ConnectionConfig> connections = new ArrayList<>();
        for (ConfigNode n : magma.children("connections")) {
            String from = n.requireString("from");
            String to = n.requireString("to");
            String kindName = n.string("kind", "conduit");
            ConnectionConfig.Kind kind = switch (kindName) {
                case "conduit" -> ConnectionConfig.Kind.CONDUIT;
                case "dike" -> ConnectionConfig.Kind.DIKE;
                default -> throw n.error("kind", "expected conduit or dike, got '" + kindName + "'");
            };
            ConnectionConfig d = ConnectionConfig.of(n.string("id", from + "-" + to), from, to, kind);
            try {
                connections.add(new ConnectionConfig(d.id(), from, to, kind, n.number("radiusM", d.radiusM()),
                        n.number("widthM", d.widthM()), n.number("strikeLengthM", d.strikeLengthM()), n.number("lengthM", d.lengthM()),
                        n.bool("open", d.open()), n.bool("freezeOnStall", d.freezeOnStall()),
                        n.number("stallRateM3PerS", d.stallRateM3PerS()), n.number("freezeSeconds", d.freezeSeconds())));
            } catch (IllegalArgumentException e) {
                throw n.error(e.getMessage());
            }
            n.finish();
        }
        try {
            return new PlumbingConfig(chambers, connections);
        } catch (IllegalArgumentException e) {
            throw magma.error(e.getMessage());
        }
    }

    static VentSite parseVent(ConfigNode v, double l) {
        String ventId = v.requireString("id");
        String kind = v.string("kind", "crater");
        Point3 position = surfacePoint(v, l);
        VentSite vent;
        try {
            vent = switch (kind) {
                case "crater" -> v.bool("emergent", false) ? VentSite.emergent(ventId, position, v.integer("radius", 1))
                        : VentSite.crater(ventId, position, v.integer("radius", 4));
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

    /**
     * A point on the ground ({@code {x, y, elevation}} map metres), or, in files from before metre positions,
     * the ground block {@code {x, y, z}} (engine axes) under it: migrated to the block's surface point.
     */
    static Point3 surfacePoint(ConfigNode p, double l) {
        if (p.has("elevation")) return mapPoint(p);
        BlockPos block = new BlockPos(p.integer("x", 0), p.integer("y", 0), p.integer("z", 0));
        p.warn("x", "is a block coordinate {x, y, z}: migrated to metres {x, y, elevation} (rewritten on the next save)");
        return Point3.surfaceOf(block, requireScale(p, l));
    }

    /** {@link #surfacePoint} of a whole node (e.g. {@code geothermal.center}). */
    static Point3 parseSurfacePoint(ConfigNode p, double l) {
        Point3 point = surfacePoint(p, l);
        p.finish();
        return point;
    }

    /**
     * A centre ({@code {x, y, elevation}} map metres), or, in files from before metre positions, the block
     * {@code {x, y, z}} (engine axes) holding it: migrated to the block's centre.
     */
    static Point3 parseCenter(ConfigNode p, double l) {
        Point3 point;
        if (p.has("elevation")) {
            point = mapPoint(p);
        } else {
            BlockPos block = new BlockPos(p.integer("x", 0), p.integer("y", 0), p.integer("z", 0));
            p.warn("x", "is a block coordinate {x, y, z}: migrated to metres {x, y, elevation} (rewritten on the next save)");
            point = Point3.ofBlock(block, requireScale(p, l));
        }
        p.finish();
        return point;
    }

    /** Map metres ({@code x} east, {@code y} north, {@code elevation} up) to the engine's axes ({@code z} south). */
    private static Point3 mapPoint(ConfigNode p) {
        return new Point3(p.number("x", 0), p.requireNumber("elevation"), -p.number("y", 0));
    }

    private static double requireScale(ConfigNode p, double l) {
        if (!(l > 0)) throw p.error("x", "block coordinates need the world's column size to migrate");
        return l;
    }

    /** Whether a definition tree still gives a position as block coordinates (written before metre positions). */
    @SuppressWarnings("unchecked")
    public static boolean hasBlockPositions(Object tree) {
        if (!(tree instanceof Map<?, ?> root)) return false;
        if (root.get("vents") instanceof List<?> vents) {
            for (Object v : vents) if (v instanceof Map<?, ?> m && !m.containsKey("elevation")) return true;
        }
        if (root.get("magma") instanceof Map<?, ?> magma) {
            if (magma.get("chamber") instanceof Map<?, ?> c && c.get("center") instanceof Map<?, ?> p && !p.containsKey("elevation")) {
                return true;
            }
            if (magma.get("chambers") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> c && c.get("center") instanceof Map<?, ?> p && !p.containsKey("elevation")) return true;
                }
            }
        }
        return root.get("geothermal") instanceof Map<?, ?> g && g.get("center") instanceof Map<?, ?> p
                && !p.containsKey("elevation");
    }

    /** A point as map metres ({@code {x, y, elevation}}; the inverse of {@link #mapPoint}). */
    static Map<String, Object> mapTree(Point3 p) {
        return WorldDefinition.map("x", p.x(), "y", p.z() == 0 ? 0.0 : -p.z(), "elevation", p.y());
    }

    // ── Building ──

    /** Scaling for this volcano: the world's (geometry only; there is one physical clock). */
    public VolcanoScaling scaling(VolcanoScaling world) {
        return world;
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
        return builder(terrain, lava, world, subsurface).build();
    }

    /**
     * The builder {@link #assemble} builds: also what a live retune derives the running volcano's new
     * configurations from ({@link VolcanoSystem.Builder#subsystemConfigs}).
     */
    public VolcanoSystem.Builder builder(TerrainModel terrain, LavaFlow lava, WorldDefinition world, Subsurface subsurface) {
        MagmaChamberConfig chamberConfig = active ? chamber : chamber.toBuilder().supplyRate(0).build();
        PlumbingConfig plumbingConfig = active ? plumbing
                : new PlumbingConfig(plumbing.chambers().stream().map(c -> c.toBuilder().supplyRate(0).build()).toList(),
                        plumbing.connections());
        VolcanoSystem.Builder b = VolcanoSystem.builder(id, vents, terrain, lava)
                .scaling(scaling(world.scaling()))
                .chamber(chamberConfig)
                .plumbing(plumbingConfig)
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
        return b;
    }

    private static <T> T copyFields(T source, T target) {
        ConfigNode node = ConfigNode.root("copy", ConfigBinder.exportFields(source, Set.of()));
        ConfigBinder.bindFields(node, target, Set.of(), Set.of());
        return target;
    }

    // ── Export ──

    /**
     * Definition of an assembled volcano, e.g. to write a world template from a built-in scenario.
     * Values the scaling derives are left out.
     */
    public static VolcanoDefinition fromSystem(VolcanoSystem system, VolcanoScaling world) {
        MagmaChamberConfig chamber = system.chamber().config();
        return new VolcanoDefinition(
                system.volcanoId(), system.volcanoId(), true, system.vents(), chamber,
                system.dikes() != null ? system.dikes().config().copy() : null,
                system.geothermal() != null
                        ? copyFields((GeothermalConfig) system.geothermal().config(), new GeothermalConfig()) : null,
                system.geothermal() != null ? Point3.surfaceOf(system.geothermal().center(), system.scaling().metersPerBlock()) : null,
                system.pyroclasticFlows() != null ? system.pyroclasticFlows().config().copy() : null,
                system.lahars() != null ? system.lahars().config().copy() : null,
                system.deformation() != null,
                system.tephra().config().copy(),
                system.ballisticFraction(),
                null, Double.POSITIVE_INFINITY, Double.NaN,
                system.deformation() != null ? system.deformation().config().stations : List.of(),
                explicitDetail(system),
                plumbingOf(system));
    }

    private static PlumbingConfig plumbingOf(VolcanoSystem system) {
        if (system.plumbing() == null) return PlumbingConfig.NONE;
        List<MagmaChamberConfig> extras = system.chambers().values().stream().map(c -> c.config()).filter(c -> !c.isMain()).toList();
        return new PlumbingConfig(extras, system.plumbing().connections());
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
            m.putAll(mapTree(v.position()));
            m.put("radius", v.craterRadius());
            if (v.emergent()) m.put("emergent", true);
            if (v.kind() == VentKind.FISSURE) {
                m.put("angleDeg", Math.toDegrees(v.fissureAngleRad()));
                m.put("length", v.fissureLength());
            }
            ventList.add(m);
        }
        root.put("vents", ventList);
        root.put("ballisticFraction", ballisticFraction);

        Map<String, Object> chamberTree = new LinkedHashMap<>();
        chamberTree.put("center", mapTree(chamber.center()));
        chamberTree.putAll(ConfigBinder.exportRecord(chamber, CHAMBER_SKIP));
        Map<String, Object> magma = new LinkedHashMap<>();
        magma.put("chamber", chamberTree);
        magma.put("conduit", ConfigBinder.exportRecord(chamber.conduit(), Set.of()));
        if (!plumbing.chambers().isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (MagmaChamberConfig x : plumbing.chambers()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.chamberId());
                m.put("center", mapTree(x.center()));
                m.putAll(ConfigBinder.exportRecord(x, CHAMBER_SKIP));
                list.add(m);
            }
            magma.put("chambers", list);
        }
        if (!plumbing.connections().isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (ConnectionConfig x : plumbing.connections()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.id());
                m.put("from", x.from());
                m.put("to", x.to());
                m.put("kind", x.kind() == ConnectionConfig.Kind.CONDUIT ? "conduit" : "dike");
                m.put("radiusM", x.radiusM());
                m.put("widthM", x.widthM());
                m.put("strikeLengthM", x.strikeLengthM());
                if (!Double.isNaN(x.lengthM())) m.put("lengthM", x.lengthM());
                m.put("open", x.open());
                m.put("freezeOnStall", x.freezeOnStall());
                m.put("stallRateM3PerS", x.stallRateM3PerS());
                m.put("freezeSeconds", x.freezeSeconds());
                list.add(m);
            }
            magma.put("connections", list);
        }
        root.put("magma", magma);

        Map<String, Object> dikeTree = new LinkedHashMap<>();
        dikeTree.put("enabled", dikes != null);
        if (dikes != null) dikeTree.putAll(ConfigBinder.exportFields(dikes, DIKE_DERIVED));
        root.put("dikes", dikeTree);

        Map<String, Object> geoTree = new LinkedHashMap<>();
        geoTree.put("enabled", geothermal != null);
        if (geothermal != null) {
            if (geothermalCenter != null) {
                geoTree.put("center", mapTree(geothermalCenter));
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
                deformation, tephra, ballisticFraction, material, radius, baseZ,
                stations, detail, plumbing);
    }

    /** Same definition with a different {@code active} flag. */
    public VolcanoDefinition withActive(boolean value) {
        return new VolcanoDefinition(id, name, value, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar,
                deformation, tephra, ballisticFraction, edificeMaterial,
                edificeRadius, edificeBaseZ, stations, detail, plumbing);
    }

    /** Same definition with another plumbing (further chambers and pathways). */
    public VolcanoDefinition withPlumbing(PlumbingConfig value) {
        return new VolcanoDefinition(id, name, active, vents, chamber, dikes, geothermal, geothermalCenter, pdc, lahar,
                deformation, tephra, ballisticFraction, edificeMaterial,
                edificeRadius, edificeBaseZ, stations, detail, value);
    }
}
