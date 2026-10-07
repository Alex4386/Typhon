package me.alex4386.typhon.engine.deformation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;

/** Parameters of the ground deformation model. Physical quantities in real units. */
public final class DeformationConfig {
    public String volcanoId;
    /** Chamber volume (m³), for the Mogi volume change. */
    public double chamberVolume;
    /** Physical depth of the Mogi source (m). */
    public double sourceDepth;
    /** Horizontal position of the source (m). */
    public double centerX;
    public double centerZ;

    /**
     * Effective elastic moduli of the volcanic crust (fractured edifice rock: a few GPa; Heap et al. 2020,
     * J. Volcanol. Geotherm. Res. 390), as for the dikes.
     */
    public double shearModulusPa = 3e9;
    public double poissonRatio = 0.25;

    public List<GeodeticStation> stations = new ArrayList<>();
    /** Seconds between {@link DeformationEvents.DeformationSample}s. */
    public double samplePeriodSeconds = 10;

    /** Write the modelled uplift into the world model's uplift field. */
    public boolean applyToTerrain = true;
    /**
     * Numerical cost cap (m): columns are updated out to where the modelled uplift falls below the reporting
     * threshold (several source depths for a Mogi source), but never beyond this distance from the source.
     */
    public double terrainRadiusM = 50_000;
    /** Seconds between terrain checks. */
    public double terrainPeriodSeconds = 30;
    /** At most this many column changes per check. */
    public int maxTerrainChangesPerCheck = 256;

    public DeformationConfig(String volcanoId, double chamberVolume, double sourceDepth, double centerX, double centerZ) {
        this.volcanoId = Objects.requireNonNull(volcanoId, "volcanoId");
        this.chamberVolume = chamberVolume;
        this.sourceDepth = sourceDepth;
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    /** Mogi source matching a chamber. */
    public static DeformationConfig forChamber(MagmaChamberConfig chamber) {
        return new DeformationConfig(chamber.volcanoId(), chamber.volume(), chamber.lithostaticDepth(),
                chamber.center().x(), chamber.center().z());
    }

    void validate() {
        if (!(chamberVolume > 0)) throw new IllegalArgumentException("chamberVolume must be > 0");
        if (!(sourceDepth > 0)) throw new IllegalArgumentException("sourceDepth must be > 0");
        if (!(shearModulusPa > 0)) throw new IllegalArgumentException("shearModulusPa must be > 0");
        if (!(poissonRatio > 0 && poissonRatio < 0.5)) throw new IllegalArgumentException("poissonRatio must be in (0, 0.5)");
        if (!(samplePeriodSeconds >= DeformationModel.STEP_SECONDS)) {
            throw new IllegalArgumentException("samplePeriodSeconds must be at least " + DeformationModel.STEP_SECONDS);
        }
        if (!(terrainPeriodSeconds >= DeformationModel.STEP_SECONDS)) {
            throw new IllegalArgumentException("terrainPeriodSeconds must be at least " + DeformationModel.STEP_SECONDS);
        }
        if (!(terrainRadiusM >= 0)) throw new IllegalArgumentException("terrainRadiusM must be >= 0");
        if (maxTerrainChangesPerCheck < 1) throw new IllegalArgumentException("maxTerrainChangesPerCheck must be >= 1");
    }
}
