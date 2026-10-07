package me.alex4386.typhon.engine.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;

/**
 * Owns the {@link WorldModel} as a subsystem: receives the host's ground ({@link GroundImport}) and persists
 * the world model. Everything is in metres; the world model is the only description of the ground.
 *
 * <p>Register it before the subsystems that depend on it so imports are applied first.
 */
public final class TerrainModel implements Subsystem {
    public static final String ID = "terrain";
    private static final double EPS = 1e-6;

    private final WorldModel world;

    /** Stand-alone terrain over a default world model (tests, simple hosts). */
    public TerrainModel() {
        this(new WorldModel(WorldSpec.defaults()));
    }

    public TerrainModel(WorldModel world) {
        this.world = Objects.requireNonNull(world);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public double periodSeconds() {
        return Double.POSITIVE_INFINITY; // command-driven
    }

    @Override
    public Object config() {
        return world.spec();
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(GroundImport.class, this::apply);
    }

    @Override
    public void step(StepContext context) {}

    /** The underlying world model. */
    public WorldModel world() {
        return world;
    }

    /** Builds unknown columns from the geology and brings known ones to the given surface and water level. */
    public void apply(GroundImport ground) {
        List<WorldModel.ColumnImport> imports = new ArrayList<>();
        for (GroundColumn c : ground.columns()) {
            if (world.isKnown(c.x(), c.z())) {
                reconcile(c);
            } else {
                imports.add(new WorldModel.ColumnImport(c.x(), c.z(), c.surfaceZ(), solidOrNull(c.cover())));
            }
        }
        world.importColumns(imports);
        for (GroundColumn c : ground.columns()) world.setWaterZ(c.x(), c.z(), c.waterZ());
    }

    private void reconcile(GroundColumn c) {
        double current = world.surfaceZ(c.x(), c.z());
        if (c.surfaceZ() > current + EPS) {
            Material material = solidOrNull(c.cover());
            if (material == null) material = MaterialTable.require(world.spec().surfaceMaterial());
            world.deposit(c.x(), c.z(), c.surfaceZ() - current, material, UnitTable.UNATTRIBUTED);
        } else if (c.surfaceZ() < current - EPS) {
            world.erode(c.x(), c.z(), current - c.surfaceZ(), false);
        }
    }

    private static Material solidOrNull(Material m) {
        return m != null && m.solid() ? m : null;
    }

    @Override
    public void saveState(StateWriter out) {
        world.save(out);
    }

    @Override
    public void loadState(StateReader in) {
        world.load(in);
    }
}
