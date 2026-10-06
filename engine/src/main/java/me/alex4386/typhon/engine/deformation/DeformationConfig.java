package me.alex4386.typhon.engine.deformation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;

/** Parameters of the ground deformation model. Physical quantities in real units. */
public final class DeformationConfig {
    public String volcanoId;
    /** Chamber volume (m³), for the Mogi volume change. */
    public double chamberVolume;
    /** Physical depth of the Mogi source (m). */
    public double sourceDepth;
    /** World column above the source. */
    public int centerX;
    public int centerZ;

    public double shearModulusPa = 3e9;
    public double poissonRatio = 0.25;
    /** Real metres per block (horizontal distances and terrain uplift, see {@link VolcanoScaling}). */
    public double metersPerBlock = VolcanoScaling.DEFAULT.metersPerBlock();

    public List<GeodeticStation> stations = new ArrayList<>();
    /** Seconds between {@link DeformationEvents.DeformationSample}s. */
    public double samplePeriodSeconds = 10;

    /** Turn whole blocks of accumulated uplift/subsidence into terrain changes. */
    public boolean applyToTerrain = true;
    /** Columns within this radius of the centre are adjusted (blocks). */
    public int terrainRadiusBlocks = 48;
    /** Seconds between terrain checks. */
    public double terrainPeriodSeconds = 30;
    /** At most this many column changes per check. */
    public int maxTerrainChangesPerCheck = 256;

    public DeformationConfig(String volcanoId, double chamberVolume, double sourceDepth, int centerX, int centerZ) {
        this.volcanoId = Objects.requireNonNull(volcanoId, "volcanoId");
        this.chamberVolume = chamberVolume;
        this.sourceDepth = sourceDepth;
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    /** Mogi source matching a chamber, scaled to the model world. */
    public static DeformationConfig forChamber(MagmaChamberConfig chamber, VolcanoScaling scaling) {
        DeformationConfig c = new DeformationConfig(chamber.volcanoId(), chamber.volume(), chamber.lithostaticDepth(),
                chamber.center().x(), chamber.center().z());
        c.metersPerBlock = scaling.metersPerBlock();
        return c;
    }

    void validate() {
        if (!(chamberVolume > 0)) throw new IllegalArgumentException("chamberVolume must be > 0");
        if (!(sourceDepth > 0)) throw new IllegalArgumentException("sourceDepth must be > 0");
        if (!(shearModulusPa > 0)) throw new IllegalArgumentException("shearModulusPa must be > 0");
        if (!(poissonRatio > 0 && poissonRatio < 0.5)) throw new IllegalArgumentException("poissonRatio must be in (0, 0.5)");
        if (!(metersPerBlock > 0)) throw new IllegalArgumentException("metersPerBlock must be > 0");
        if (!(samplePeriodSeconds >= DeformationModel.STEP_SECONDS)) {
            throw new IllegalArgumentException("samplePeriodSeconds must be at least " + DeformationModel.STEP_SECONDS);
        }
        if (!(terrainPeriodSeconds >= DeformationModel.STEP_SECONDS)) {
            throw new IllegalArgumentException("terrainPeriodSeconds must be at least " + DeformationModel.STEP_SECONDS);
        }
        if (terrainRadiusBlocks < 0) throw new IllegalArgumentException("terrainRadiusBlocks must be >= 0");
        if (maxTerrainChangesPerCheck < 1) throw new IllegalArgumentException("maxTerrainChangesPerCheck must be >= 1");
    }
}
