package me.alex4386.typhon.engine.magma.plumbing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;

/**
 * Magma moving between the chambers of one volcano's plumbing.
 *
 * <p>Each open pathway carries {@code Q = C·ΔP} from its source to its receiving chamber, with the
 * driving pressure the difference of absolute pressures less the magma column between them:
 * {@code ΔP = (P_from − P_to) + (ρ_rock − ρ_magma)·g·(d_from − d_to)}, where {@code P} are the chambers'
 * overpressures over lithostatic and {@code d} their depths (lithostatic minus magmastatic difference).
 * Flow is one way ({@code Q ≥ 0}); a reversed pressure difference stops it. The conductance is
 * Poiseuille's: a pipe {@code C = π r⁴ / (8 η L)}, a slot {@code C = w³ ℓ / (12 η L)}, with {@code η}
 * the source magma's viscosity and {@code L} the path length.
 *
 * <p>The volume moved in a step is exact for the pair's linear relaxation: with storativities
 * {@code S = V·β} (m³/MPa) the driving pressure decays as {@code exp(−C(1/S_f + 1/S_t)·t)}, so the step
 * moves {@code ΔP₀ (1 − e^{−k Δt}) / (1/S_f + 1/S_t)} and never overshoots the head equilibrium, at any
 * step length. Pathways are applied in id order (deterministic operator splitting).
 *
 * <p>References: Poiseuille flow in feeders (Wilson &amp; Head 1981, JGR 86); coupled reservoirs with
 * hydraulic connections (Rivalta 2010, JGR 115; Le Mével et al. 2016 / Reverso et al. 2014 two-chamber
 * models); Kīlauea summit storage (Poland et al. 2014).
 */
public final class MagmaTransfer implements Subsystem {
    private static final double GRAVITY = 9.81;

    private final String volcanoId;
    private final Map<String, MagmaChamber> chambers;
    private final MagmaChamber clock;
    private List<ConnectionConfig> connections;
    /** Per pathway, by id. */
    private final Map<String, Link> links = new TreeMap<>();
    private double lastTime;

    /** Live state of one pathway. */
    static final class Link {
        double rateM3PerS;
        double transferredM3;
        double stalledSeconds;
        boolean frozen;
    }

    /** Flow along one pathway (m³/s). */
    public record Flow(String id, String from, String to, double rateM3PerS, double transferredM3, boolean open, boolean frozen,
            double drivingPressureMPa, double lengthM) {}

    /**
     * @param chambers every chamber of the volcano by id (the main one included)
     * @param clock the chamber whose state sets the physical time step (the main chamber)
     */
    public MagmaTransfer(String volcanoId, Map<String, MagmaChamber> chambers, MagmaChamber clock, List<ConnectionConfig> connections) {
        this.volcanoId = Objects.requireNonNull(volcanoId);
        this.chambers = Map.copyOf(chambers);
        this.clock = Objects.requireNonNull(clock);
        setConnections(connections);
    }

    private void setConnections(List<ConnectionConfig> list) {
        List<ConnectionConfig> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(ConnectionConfig::id));
        for (ConnectionConfig c : sorted) {
            if (!chambers.containsKey(c.from()) || !chambers.containsKey(c.to())) {
                throw new IllegalArgumentException("connection " + c.id() + " joins unknown chambers");
            }
            links.computeIfAbsent(c.id(), k -> new Link());
        }
        links.keySet().retainAll(sorted.stream().map(ConnectionConfig::id).toList());
        this.connections = List.copyOf(sorted);
    }

    public static String defaultId(String volcanoId) {
        return "plumbing:" + volcanoId;
    }

    @Override
    public String id() {
        return defaultId(volcanoId);
    }

    @Override
    public String concurrencyLane() {
        return "volcano:" + volcanoId;
    }

    @Override
    public double periodSeconds() {
        return clock.config().stepPeriodSeconds();
    }

    @Override
    public Object config() {
        return connections;
    }

    /** Pathway settings change live; adding or removing pathways (different ids) needs a rebuild. */
    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof List<?> list)) return false;
        List<ConnectionConfig> next = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof ConnectionConfig cc)) return false;
            next.add(cc);
        }
        if (!next.stream().map(ConnectionConfig::id).sorted().toList().equals(connections.stream().map(ConnectionConfig::id).toList())) {
            return false;
        }
        setConnections(next);
        return true;
    }

    /** Path length of a pathway (m): its configured length, else the distance between the chamber centres. */
    public double lengthM(ConnectionConfig c) {
        if (!Double.isNaN(c.lengthM())) return c.lengthM();
        MagmaChamber a = chambers.get(c.from());
        MagmaChamber b = chambers.get(c.to());
        Point3 pa = a.config().center();
        Point3 pb = b.config().center();
        double h = Math.hypot(pa.x() - pb.x(), pa.z() - pb.z());
        double v = a.config().lithostaticDepth() - b.config().lithostaticDepth();
        return Math.max(1, Math.hypot(h, v));
    }

    /** Hydraulic conductance (m³/s per Pa) of a pathway for the source magma's viscosity. */
    public double conductance(ConnectionConfig c) {
        MagmaChamber from = chambers.get(c.from());
        double eta = StrictMath.pow(10, from.viscosityLog10());
        double length = lengthM(c);
        return c.kind() == ConnectionConfig.Kind.CONDUIT
                ? Math.PI * StrictMath.pow(c.radiusM(), 4) / (8 * eta * length)
                : StrictMath.pow(c.widthM(), 3) * c.strikeLengthM() / (12 * eta * length);
    }

    /**
     * Flow (m³/s) below which a pathway counts as stalled: the configured value, or the flow too slow to keep it
     * open thermally. Magma crossing the path length {@code L} faster than heat diffuses across its half-width
     * {@code a} stays molten ({@code a²/κ > L/v}): a conduit needs {@code Q > πκL}, a dike of strike {@code S}
     * and width {@code w} {@code Q > 4κLS/w} (Delaney &amp; Pollard 1982; Bruce &amp; Huppert 1989).
     */
    public double stallRateM3PerS(ConnectionConfig c) {
        if (!Double.isNaN(c.stallRateM3PerS())) return c.stallRateM3PerS();
        double kappa = MagmaChamber.THERMAL_DIFFUSIVITY;
        double length = lengthM(c);
        return c.kind() == ConnectionConfig.Kind.CONDUIT
                ? Math.PI * kappa * length
                : 4 * kappa * length * c.strikeLengthM() / c.widthM();
    }

    /**
     * Seconds of stall before a pathway freezes shut: the configured value, or the conductive solidification time
     * across its half-width {@code a}, {@code (a²/κ)(1 + L/(c ΔT))} with the latent heat {@code L} slowing it
     * (Turcotte &amp; Schubert 2002, §4.17; ΔT from the source magma to the host rock): weeks for a metre-scale
     * conduit, days for a dike.
     */
    public double freezeSeconds(ConnectionConfig c) {
        if (!Double.isNaN(c.freezeSeconds())) return c.freezeSeconds();
        MagmaChamber from = chambers.get(c.from());
        double half = c.kind() == ConnectionConfig.Kind.CONDUIT ? c.radiusM() : c.widthM() / 2;
        double cooling = Math.max(1, from.temperatureC() - from.config().wallTemperatureC());
        double stefan = 1 + MagmaChamber.LATENT_HEAT_CRYSTALLISATION / (MagmaChamber.MELT_HEAT_CAPACITY * cooling);
        return half * half / MagmaChamber.THERMAL_DIFFUSIVITY * stefan;
    }

    /** Pressure (MPa) driving magma from the source to the receiving chamber of a pathway. */
    public double drivingPressureMPa(ConnectionConfig c) {
        MagmaChamber from = chambers.get(c.from());
        MagmaChamber to = chambers.get(c.to());
        // the crust's weight between the two depths, less the magma column's (the source's crust column)
        double deep = from.config().lithostaticDepth();
        double shallow = to.config().lithostaticDepth();
        double head = from.crust().pressureMPa(deep) - from.crust().pressureMPa(shallow)
                - from.meltDensityKgPerM3() * GRAVITY * (deep - shallow) / 1e6;
        return from.overpressureMPa() - to.overpressureMPa() + head;
    }

    @Override
    public void step(StepContext context) {
        lastTime = context.time();
        double dt = context.dtSeconds();
        if (!(dt > 0)) return;
        for (ConnectionConfig c : connections) {
            Link link = links.get(c.id());
            link.rateM3PerS = 0;
            if (!c.open() || link.frozen) continue;
            MagmaChamber from = chambers.get(c.from());
            MagmaChamber to = chambers.get(c.to());
            double drive = drivingPressureMPa(c);
            double moved = 0;
            if (drive > 0) {
                double sFrom = from.volumeM3() * from.effectiveCompressibility(); // m³ per MPa
                double sTo = to.volumeM3() * to.effectiveCompressibility();
                double inv = 1 / sFrom + 1 / sTo;
                double k = conductance(c) * 1e6 * inv; // 1/s
                moved = drive * -Math.expm1(-k * dt) / inv;
                moved = Math.min(moved, 0.5 * from.volumeM3()); // numerical guard: never drain a chamber in one step
            }
            if (moved > 0) {
                double rate = moved / dt;
                double t = from.temperatureC();
                double si = from.silicaWt();
                double w = from.bulkWaterWt();
                double co2 = from.bulkCo2Wt();
                double x = from.crystalFraction();
                from.transferOut(moved);
                to.transferIn(moved, rate, t, si, w, co2, x);
                link.rateM3PerS = rate;
                link.transferredM3 += moved;
            }
            if (c.freezeOnStall()) {
                if (link.rateM3PerS < stallRateM3PerS(c)) {
                    link.stalledSeconds += dt;
                    if (link.stalledSeconds >= freezeSeconds(c)) {
                        link.frozen = true;
                        Outbox out = context.outbox();
                        out.emit(new PlumbingEvents.ConnectionFroze(context.time(), volcanoId, c.id()));
                    }
                } else {
                    link.stalledSeconds = 0;
                }
            }
        }
    }

    /** Flows along every pathway, in id order. */
    public List<Flow> flows() {
        List<Flow> out = new ArrayList<>();
        for (ConnectionConfig c : connections) {
            Link l = links.get(c.id());
            out.add(new Flow(c.id(), c.from(), c.to(), l.rateM3PerS, l.transferredM3, c.open(), l.frozen, drivingPressureMPa(c), lengthM(c)));
        }
        return out;
    }

    public List<ConnectionConfig> connections() {
        return connections;
    }

    /** Thaws a frozen pathway (e.g. when the user reopens it). */
    public void thaw(String id) {
        Link l = links.get(id);
        if (l != null) {
            l.frozen = false;
            l.stalledSeconds = 0;
        }
    }

    @Override
    public Object snapshot() {
        Map<String, Flow> out = new LinkedHashMap<>();
        for (Flow f : flows()) out.put(f.id(), f);
        return out;
    }

    @Override
    public void saveState(StateWriter out) {
        JsonObject o = out.json();
        o.addProperty("lastTime", lastTime);
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Link> e : links.entrySet()) {
            JsonObject l = new JsonObject();
            l.addProperty("id", e.getKey());
            l.addProperty("rate", e.getValue().rateM3PerS);
            l.addProperty("transferred", e.getValue().transferredM3);
            l.addProperty("stalled", e.getValue().stalledSeconds);
            l.addProperty("frozen", e.getValue().frozen);
            arr.add(l);
        }
        o.add("links", arr);
    }

    @Override
    public void loadState(StateReader in) {
        JsonObject o = in.json();
        lastTime = o.get("lastTime").getAsDouble();
        for (JsonElement e : o.getAsJsonArray("links")) {
            JsonObject l = e.getAsJsonObject();
            Link link = links.get(l.get("id").getAsString());
            if (link == null) continue; // a pathway removed since
            link.rateM3PerS = l.get("rate").getAsDouble();
            link.transferredM3 = l.get("transferred").getAsDouble();
            link.stalledSeconds = l.get("stalled").getAsDouble();
            link.frozen = l.get("frozen").getAsBoolean();
        }
    }
}
