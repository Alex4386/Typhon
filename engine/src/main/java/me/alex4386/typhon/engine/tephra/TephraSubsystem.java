package me.alex4386.typhon.engine.tephra;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.SimTime;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.tephra.TephraCommands.LaunchBomb;
import me.alex4386.typhon.engine.tephra.TephraCommands.SetWind;
import me.alex4386.typhon.engine.tephra.TephraCommands.StartExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraCommands.StopExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraEvents.AshFall;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLanded;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLaunched;
import me.alex4386.typhon.engine.tephra.TephraEvents.ExplosivePhaseChanged;
import me.alex4386.typhon.engine.tephra.TephraEvents.PlumeColumn;
import me.alex4386.typhon.engine.tephra.TephraEvents.VolcanicLightning;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;

/**
 * Explosive tephra: ballistic bombs, the eruption column, and wind-driven ash fall.
 *
 * <p><b>Bombs</b> are launched from the vent of the active {@link ExplosivePhase} at a Poisson rate
 * set by the ballistic share of the mass eruption rate, with log-normal diameters, near-vertical
 * launch angles and a gas-thrust exit speed ({@link Ballistics#gasThrustExitSpeed}) scaled by
 * {@link TephraConfig#ballisticSpeedScale}. They fly under gravity and quadratic drag relative to the
 * wind ({@link Ballistics#rk4}, {@link TephraConfig#substepsPerTick} sub-steps per tick) until they
 * cross the ground of the {@link TerrainModel}; where the terrain is unknown the ground is taken to
 * be at launch height. On impact a crater of radius {@code k·E^(1/3)} is dug and bombs of at least
 * {@link TephraConfig#minBlockDiameter} leave a {@code magma_block} that later cools into rock chosen
 * by silica content.
 *
 * <p><b>Ash</b>: every {@link TephraConfig#ashStepTicks} ticks the non-ballistic mass is injected
 * around the vent at the plume height from {@link PlumeModel}, transported and settled on an
 * {@link AshGrid}, and the accumulated deposit is turned into block changes via the
 * {@link AshPalette}. The subsystem emits {@link PlumeColumn}, {@link AshFall} and
 * {@link VolcanicLightning} events for hosts to render.
 *
 * <p>Must be registered after the {@link TerrainModel} it reads.
 */
public final class TephraSubsystem implements Subsystem {
    private static final BlockId MAGMA_BLOCK = BlockId.minecraft("magma_block");
    private static final BlockId WATER = BlockId.minecraft("water");
    private static final BlockId BEDROCK = BlockId.minecraft("bedrock");

    /** Mass accounting for airborne tephra (kg). emitted = airborne + deposited + exported + discarded. */
    public record MassBudget(double emitted, double airborne, double deposited, double exported, double discarded) {}

    private record Cooling(BlockPos pos, long dueTick, BlockId target) {}

    private record Landing(Vec3d position, Vec3d velocity) {}

    private final String id;
    private final TerrainModel terrain;
    private final TephraConfig config;
    private final WindField wind = new WindField(5, 0, 0);
    private final List<EngineCommand> pending = new ArrayList<>();
    private final List<Bomb> bombs = new ArrayList<>();
    private final List<Cooling> coolings = new ArrayList<>();

    private ExplosivePhase phase;
    private long nextBombId = 1;
    private AshGrid grid;

    public TephraSubsystem(String id, TerrainModel terrain, TephraConfig config) {
        this.id = Objects.requireNonNull(id, "id");
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.config = config.copy();
        this.config.validate();
    }

    public TephraSubsystem(String id, TerrainModel terrain) {
        this(id, terrain, new TephraConfig());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(StartExplosivePhase.class, c -> { if (c.target().equals(id)) pending.add(c); });
        bus.register(StopExplosivePhase.class, c -> { if (c.target().equals(id)) pending.add(c); });
        bus.register(SetWind.class, c -> { if (c.target().equals(id)) pending.add(c); });
        bus.register(LaunchBomb.class, c -> { if (c.target().equals(id)) pending.add(c); });
    }

    // ── Direct API (same effect as the commands; applied at this subsystem's next step) ──

    public void startPhase(ExplosivePhase phase) {
        pending.add(new StartExplosivePhase(id, phase));
    }

    public void stopPhase() {
        pending.add(new StopExplosivePhase(id));
    }

    public void setWind(double speed, double directionRad, double variability) {
        pending.add(new SetWind(id, speed, directionRad, variability));
    }

    public void launchBomb(Vec3d start, Vec3d velocity, double diameter, double silicaWt) {
        pending.add(new LaunchBomb(id, start, velocity, diameter, silicaWt));
    }

    // ── Queries ──

    public ExplosivePhase activePhase() {
        return phase;
    }

    public int inFlightBombs() {
        return bombs.size();
    }

    public int pendingCoolings() {
        return coolings.size();
    }

    public WindField wind() {
        return wind;
    }

    /** Current plume height above the vent in blocks (0 when no phase is active). */
    public double plumeHeight() {
        return phase == null || grid == null ? 0 : grid.plumeHeight;
    }

    /** Ash deposit thickness (m) at a column; 0 outside the ash grid. */
    public double depositThickness(int x, int z) {
        if (grid == null) return 0;
        int cell = grid.cellAt(x, z);
        return cell < 0 ? 0 : grid.thickness(cell, config.depositBulkDensity);
    }

    /** Suspended ash load (kg/m²) above a column; 0 outside the ash grid. */
    public double airborneLoad(int x, int z) {
        if (grid == null) return 0;
        int cell = grid.cellAt(x, z);
        return cell < 0 ? 0 : grid.airborneAt(cell) / grid.cellArea();
    }

    public MassBudget massBudget() {
        if (grid == null) return new MassBudget(0, 0, 0, 0, 0);
        return new MassBudget(grid.emitted, grid.airborneTotal(), grid.deposited, grid.exported, grid.discarded);
    }

    AshGrid grid() {
        return grid;
    }

    TephraConfig config() {
        return config;
    }

    // ── Simulation ──

    @Override
    public void step(StepContext context) {
        processPending(context);
        launchFromPhase(context);
        advanceBombs(context);
        runCoolings(context);
        long tick = context.tick();
        if (tick % config.ashStepTicks == 0) ashStep(context);
        if (grid != null && tick % config.ashEventTicks == 0) emitAshFall(context);
    }

    private void processPending(StepContext context) {
        for (EngineCommand command : pending) {
            switch (command) {
                case StartExplosivePhase start -> {
                    phase = start.phase();
                    ensureGrid(phase.vent().position());
                    context.outbox().emit(new ExplosivePhaseChanged(context.tick(), id, true, phase.massEruptionRate()));
                }
                case StopExplosivePhase stop -> {
                    if (phase != null) {
                        phase = null;
                        context.outbox().emit(new ExplosivePhaseChanged(context.tick(), id, false, 0));
                    }
                }
                case SetWind set -> wind.set(set.speed(), set.directionRad(), set.variability(), context.random());
                case LaunchBomb launch -> launch(context, launch.start(), launch.velocity(), launch.diameter(), launch.silicaWt());
                default -> throw new IllegalStateException("Unexpected command " + command);
            }
        }
        pending.clear();
    }

    private void ensureGrid(BlockPos center) {
        if (grid == null) grid = AshGrid.centeredOn(center, config.cellSize, config.gridCells);
    }

    // ── Bombs ──

    private void launchFromPhase(StepContext context) {
        if (phase == null || phase.massEruptionRate() <= 0 || phase.ballisticFraction() <= 0) return;
        SimRandom random = context.random();

        double sigma = config.bombDiameterSigma;
        double meanMass = Ballistics.sphereMass(config.bombMedianDiameter, config.bombDensity)
                * StrictMath.exp(4.5 * sigma * sigma);
        double rate = Math.min(config.maxBombsPerSecond, phase.massEruptionRate() * config.massScale * phase.ballisticFraction() / meanMass);
        int count = random.nextPoisson(rate / SimTime.TICKS_PER_SECOND);
        if (count == 0) return;

        double physical = Ballistics.gasThrustExitSpeed(phase.gasFraction(), phase.temperatureC(), phase.overpressureMPa());
        double exitSpeed = Math.max(config.minExitSpeed, Math.min(config.maxExitSpeed, physical)) * config.ballisticSpeedScale;

        for (int n = 0; n < count; n++) {
            double diameter = clamp(
                    config.bombMedianDiameter * StrictMath.exp(sigma * random.nextGaussian()),
                    config.minBombDiameter,
                    config.maxBombDiameter);
            // Large clasts decouple from the gas jet and leave more slowly.
            double speed = exitSpeed * StrictMath.exp(0.15 * random.nextGaussian()) / Math.sqrt(1 + diameter);
            double zenith = Math.min(
                    Math.abs(random.nextGaussian()) * Math.toRadians(config.launchAngleSigmaDeg),
                    Math.toRadians(config.maxLaunchAngleDeg));
            double azimuth = random.nextDouble(0, 2 * Math.PI);
            double horizontal = speed * StrictMath.sin(zenith);
            Vec3d velocity = new Vec3d(
                    horizontal * StrictMath.cos(azimuth), speed * StrictMath.cos(zenith), horizontal * StrictMath.sin(azimuth));
            launch(context, sampleVentPoint(phase.vent(), random), velocity, diameter, phase.silicaWt());
        }
    }

    private static Vec3d sampleVentPoint(VentSite vent, SimRandom random) {
        BlockPos p = vent.position();
        double cx = p.x() + 0.5, cz = p.z() + 0.5, y = p.y() + 1;
        if (vent.kind() == VentKind.FISSURE) {
            double along = (random.nextDouble() - 0.5) * vent.fissureLength();
            double across = (random.nextDouble() - 0.5) * Math.max(1, vent.craterRadius());
            double dx = StrictMath.cos(vent.fissureAngleRad()), dz = StrictMath.sin(vent.fissureAngleRad());
            return new Vec3d(cx + along * dx - across * dz, y, cz + along * dz + across * dx);
        }
        double r = vent.craterRadius() * Math.sqrt(random.nextDouble());
        double a = random.nextDouble(0, 2 * Math.PI);
        return new Vec3d(cx + r * StrictMath.cos(a), y, cz + r * StrictMath.sin(a));
    }

    private void launch(StepContext context, Vec3d start, Vec3d velocity, double diameter, double silicaWt) {
        if (!(diameter > 0)) throw new IllegalArgumentException("diameter must be positive");
        double k = Ballistics.dragFactor(diameter, config.bombDensity, config.dragCoefficient, config.airDensity);
        double[] s = {start.x(), start.y(), start.z(), velocity.x(), velocity.y(), velocity.z()};
        Bomb bomb = new Bomb(
                nextBombId++, s, diameter, k, silicaWt, (int) Math.floor(start.y()) - 1, context.tick());

        // Predict the flight on a copy so hosts can render it; identical unless terrain changes mid-flight.
        Bomb probe = bomb.copy();
        int maxTicks = maxFlightTicks();
        int ticks = 0;
        Landing landing = null;
        while (ticks < maxTicks && landing == null) {
            landing = advance(probe, context.tick() + ticks);
            ticks++;
        }
        context.outbox().emit(new BombLaunched(
                context.tick(),
                id,
                bomb.id,
                start,
                velocity,
                diameter,
                k,
                ticks,
                landing == null ? probe.position() : landing.position()));
        bombs.add(bomb);
    }

    private int maxFlightTicks() {
        return (int) SimTime.secondsToTicks(config.maxFlightSeconds);
    }

    private double surfaceTop(double x, double z, Bomb bomb) {
        return terrain.groundY((int) Math.floor(x), (int) Math.floor(z), bomb.fallbackGroundY) + 1;
    }

    /** Integrates one tick; returns the landing point if the bomb reached the ground. */
    private Landing advance(Bomb bomb, long tick) {
        Vec3d w = wind.at(tick);
        double dt = 1.0 / SimTime.TICKS_PER_SECOND / config.substepsPerTick;
        double[] s = bomb.s;
        for (int sub = 0; sub < config.substepsPerTick; sub++) {
            double px = s[0], py = s[1], pz = s[2], pvx = s[3], pvy = s[4], pvz = s[5];
            double f0 = py - surfaceTop(px, pz, bomb);
            Ballistics.rk4(s, dt, bomb.dragFactor, config.gravity, w.x(), w.z());
            double f1 = s[1] - surfaceTop(s[0], s[2], bomb);
            if (f1 < 0) {
                double t = f0 > 0 ? f0 / (f0 - f1) : 0;
                return new Landing(
                        new Vec3d(px + t * (s[0] - px), py + t * (s[1] - py), pz + t * (s[2] - pz)),
                        new Vec3d(pvx + t * (s[3] - pvx), pvy + t * (s[4] - pvy), pvz + t * (s[5] - pvz)));
            }
        }
        return null;
    }

    private void advanceBombs(StepContext context) {
        long tick = context.tick();
        int maxTicks = maxFlightTicks();
        Iterator<Bomb> it = bombs.iterator();
        while (it.hasNext()) {
            Bomb bomb = it.next();
            Landing landing = advance(bomb, tick);
            if (landing != null) {
                it.remove();
                land(context, bomb, landing);
            } else if (tick - bomb.launchTick + 1 >= maxTicks) {
                it.remove();
            }
        }
    }

    private void land(StepContext context, Bomb bomb, Landing landing) {
        int x = (int) Math.floor(landing.position().x());
        int z = (int) Math.floor(landing.position().z());
        double speed = landing.velocity().length();
        double energy = 0.5 * Ballistics.sphereMass(bomb.diameter, config.bombDensity) * speed * speed;
        double radius = Ballistics.craterRadius(energy, config.craterCoefficient);
        int impactGround = terrain.groundY(x, z, bomb.fallbackGroundY);

        double dug = 0;
        if (terrain.column(x, z) != null) {
            if (radius >= 1) {
                dig(context, x, z, radius);
                dug = radius;
            }
            if (bomb.diameter >= config.minBlockDiameter) placeBomb(context, x, z, bomb);
        }
        context.outbox().emit(new BombLanded(
                context.tick(), id, bomb.id, new BlockPos(x, impactGround, z), speed, energy, bomb.diameter, dug));
    }

    /**
     * Paraboloid crater, depth 0.5·r·(1 − (d/r)²). The newly exposed floor keeps the old surface id in
     * the terrain model (the engine does not know what lies beneath).
     */
    private void dig(StepContext context, int x, int z, double radius) {
        int reach = (int) Math.ceil(radius);
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) continue;
                int depth = (int) Math.round(0.5 * radius * (1 - (d / radius) * (d / radius)));
                if (depth < 1) continue;
                TerrainColumn column = terrain.column(x + dx, z + dz);
                if (column == null || column.surface().equals(BEDROCK) || column.surface().equals(BlockId.AIR)) continue;
                for (int k = 0; k < depth; k++) {
                    int y = column.groundY() - k;
                    BlockId to = column.waterY() != TerrainColumn.NO_WATER && y <= column.waterY() ? WATER : BlockId.AIR;
                    BlockId expected = k == 0 ? column.surface() : null;
                    context.outbox().setBlock(new BlockChange(
                            new BlockPos(x + dx, y, z + dz), expected, BlockState.of(to)));
                }
                terrain.setGround(x + dx, z + dz, column.groundY() - depth, column.surface());
            }
        }
    }

    private void placeBomb(StepContext context, int x, int z, Bomb bomb) {
        TerrainColumn column = terrain.column(x, z);
        int y = column.groundY() + 1;
        if (y > BlockPos.MAX_Y) return;
        boolean inWater = column.waterY() != TerrainColumn.NO_WATER && y <= column.waterY();
        BlockPos pos = new BlockPos(x, y, z);
        context.outbox().setBlock(BlockChange.replace(pos, inWater ? WATER : BlockId.AIR, MAGMA_BLOCK));
        terrain.setGround(x, z, y, MAGMA_BLOCK);

        double seconds = Math.max(config.minCoolingSeconds, config.coolingSecondsPerSquareMeter * bomb.diameter * bomb.diameter);
        if (inWater) seconds *= config.waterCoolingFactor;
        coolings.add(new Cooling(pos, context.tick() + Math.max(1, SimTime.secondsToTicks(seconds)),
                Ballistics.cooledBombRock(bomb.silicaWt)));
    }

    private void runCoolings(StepContext context) {
        long tick = context.tick();
        Iterator<Cooling> it = coolings.iterator();
        while (it.hasNext()) {
            Cooling cooling = it.next();
            if (cooling.dueTick() > tick) continue;
            it.remove();
            BlockPos pos = cooling.pos();
            context.outbox().setBlock(BlockChange.replace(pos, MAGMA_BLOCK, cooling.target()));
            TerrainColumn column = terrain.column(pos.x(), pos.z());
            if (column != null && column.groundY() == pos.y() && column.surface().equals(MAGMA_BLOCK)) {
                terrain.setGround(pos.x(), pos.z(), pos.y(), cooling.target());
            }
        }
    }

    // ── Ash ──

    private void ashStep(StepContext context) {
        double dt = SimTime.ticksToSeconds(config.ashStepTicks);
        if (phase != null) {
            BlockPos vent = phase.vent().position();
            ensureGrid(vent);
            double height = PlumeModel.minecraftHeight(phase.massEruptionRate(), vent.y(), config);
            double sigma = Math.max(config.cellSize * 0.5, 0.25 * height);
            grid.plumeHeight = height;
            grid.inject(
                    phase.massEruptionRate() * config.massScale * (1 - phase.ballisticFraction()) * dt,
                    phase.grainSize().fractions(),
                    vent.x() + 0.5,
                    vent.z() + 0.5,
                    sigma);
            BlockPos base = vent.offset(0, 1, 0);
            context.outbox().emit(new PlumeColumn(
                    context.tick(), id, base, base.y() + (int) Math.round(height), 2 * sigma, phase.massEruptionRate()));
            lightning(context, base, height, sigma, dt);
        }
        if (grid == null) return;

        if (grid.airborneTotal() > 0) {
            grid.transport(dt, wind.at(context.tick()), config.diffusivity);
            grid.settle(dt, config.settlingVelocities, grid.plumeHeight);
            if (phase == null && grid.airborneTotal() < config.minAirborneMass) grid.discardAirborne();
        }
        grid.applyDeposits(terrain, context.outbox(), config);
    }

    private void lightning(StepContext context, BlockPos base, double height, double sigma, double dt) {
        double rate = phase.massEruptionRate();
        if (rate < config.lightningMinMassEruptionRate || height < 1) return;
        double flashesPerSecond = Math.min(config.maxLightningPerSecond, config.lightningPerMassRate * rate);
        SimRandom random = context.random();
        int flashes = random.nextPoisson(flashesPerSecond * dt);
        for (int i = 0; i < flashes; i++) {
            int x = (int) Math.floor(base.x() + 0.5 + random.nextGaussian() * sigma * 0.5);
            int z = (int) Math.floor(base.z() + 0.5 + random.nextGaussian() * sigma * 0.5);
            int y = base.y() + (int) Math.round(height * (0.4 + 0.6 * random.nextDouble()));
            context.outbox().emit(new VolcanicLightning(context.tick(), id, new BlockPos(x, Math.min(y, BlockPos.MAX_Y), z)));
        }
    }

    private void emitAshFall(StepContext context) {
        int r = Math.max(1, config.ashEventRegionCells);
        int regions = (grid.cells + r - 1) / r;
        for (int rj = 0; rj < regions; rj++) {
            for (int ri = 0; ri < regions; ri++) {
                double rate = 0, load = 0;
                int count = 0;
                for (int j = rj * r; j < Math.min(grid.cells, (rj + 1) * r); j++) {
                    for (int i = ri * r; i < Math.min(grid.cells, (ri + 1) * r); i++) {
                        int cell = grid.index(i, j);
                        rate += grid.depositionRate[cell];
                        load += grid.airborneAt(cell);
                        count++;
                    }
                }
                double area = count * grid.cellArea();
                double fallRate = rate / area;
                double airborneLoad = load / area;
                if (fallRate < config.ashFallRateThreshold && airborneLoad < config.ashLoadThreshold) continue;
                int half = r * grid.cellSize / 2;
                BlockPos center = new BlockPos(
                        grid.originX + ri * r * grid.cellSize + half, 0, grid.originZ + rj * r * grid.cellSize + half);
                context.outbox().emit(new AshFall(context.tick(), id, center, half, fallRate, airborneLoad));
            }
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ── Persistence ──

    @Override
    public void saveState(JsonObject out) {
        if (phase != null) out.add("phase", savePhase(phase));
        JsonObject windState = new JsonObject();
        wind.save(windState);
        out.add("wind", windState);
        out.addProperty("nextBombId", nextBombId);
        JsonArray bombStates = new JsonArray();
        for (Bomb bomb : bombs) bombStates.add(bomb.save());
        out.add("bombs", bombStates);
        JsonArray coolingStates = new JsonArray();
        for (Cooling cooling : coolings) {
            JsonObject c = new JsonObject();
            c.addProperty("x", cooling.pos().x());
            c.addProperty("y", cooling.pos().y());
            c.addProperty("z", cooling.pos().z());
            c.addProperty("due", cooling.dueTick());
            c.addProperty("target", cooling.target().toString());
            coolingStates.add(c);
        }
        out.add("coolings", coolingStates);
        if (grid != null) {
            JsonObject gridState = new JsonObject();
            grid.save(gridState);
            out.add("ash", gridState);
        }
    }

    @Override
    public void loadState(JsonObject in) {
        phase = in.has("phase") ? loadPhase(in.getAsJsonObject("phase")) : null;
        wind.load(in.getAsJsonObject("wind"));
        nextBombId = in.get("nextBombId").getAsLong();
        bombs.clear();
        for (JsonElement e : in.getAsJsonArray("bombs")) bombs.add(Bomb.load(e.getAsJsonObject()));
        coolings.clear();
        for (JsonElement e : in.getAsJsonArray("coolings")) {
            JsonObject c = e.getAsJsonObject();
            coolings.add(new Cooling(
                    new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt()),
                    c.get("due").getAsLong(),
                    BlockId.parse(c.get("target").getAsString())));
        }
        grid = null;
        if (in.has("ash")) {
            grid = AshGrid.load(in.getAsJsonObject("ash"));
            if (grid.cellSize != config.cellSize || grid.cells != config.gridCells) {
                throw new IllegalStateException("Saved ash grid " + grid.cells + "x" + grid.cellSize
                        + " does not match config " + config.gridCells + "x" + config.cellSize);
            }
        }
    }

    private static JsonObject savePhase(ExplosivePhase p) {
        JsonObject out = new JsonObject();
        VentSite v = p.vent();
        JsonObject vent = new JsonObject();
        vent.addProperty("id", v.id());
        vent.addProperty("x", v.position().x());
        vent.addProperty("y", v.position().y());
        vent.addProperty("z", v.position().z());
        vent.addProperty("kind", v.kind().name());
        vent.addProperty("craterRadius", v.craterRadius());
        vent.addProperty("fissureAngle", v.fissureAngleRad());
        vent.addProperty("fissureLength", v.fissureLength());
        out.add("vent", vent);
        out.addProperty("massEruptionRate", p.massEruptionRate());
        out.addProperty("gasFraction", p.gasFraction());
        out.addProperty("overpressure", p.overpressureMPa());
        out.addProperty("temperature", p.temperatureC());
        out.addProperty("silica", p.silicaWt());
        out.addProperty("ballisticFraction", p.ballisticFraction());
        JsonArray fractions = new JsonArray();
        for (double w : p.grainSize().weights()) fractions.add(w);
        out.add("grainSize", fractions);
        return out;
    }

    private static ExplosivePhase loadPhase(JsonObject in) {
        JsonObject v = in.getAsJsonObject("vent");
        VentSite vent = new VentSite(
                v.get("id").getAsString(),
                new BlockPos(v.get("x").getAsInt(), v.get("y").getAsInt(), v.get("z").getAsInt()),
                VentKind.valueOf(v.get("kind").getAsString()),
                v.get("craterRadius").getAsInt(),
                v.get("fissureAngle").getAsDouble(),
                v.get("fissureLength").getAsInt());
        JsonArray f = in.getAsJsonArray("grainSize");
        double[] fractions = new double[f.size()];
        for (int i = 0; i < fractions.length; i++) fractions[i] = f.get(i).getAsDouble();
        return new ExplosivePhase(
                vent,
                in.get("massEruptionRate").getAsDouble(),
                in.get("gasFraction").getAsDouble(),
                in.get("overpressure").getAsDouble(),
                in.get("temperature").getAsDouble(),
                in.get("silica").getAsDouble(),
                in.get("ballisticFraction").getAsDouble(),
                new GrainSizeDistribution(fractions));
    }
}
