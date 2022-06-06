package me.alex4386.plugin.typhon.volcano.erupt;

public enum VolcanoEruptCauseType {
    MAGMATIC,
    PHREATOMAGMATIC,
    PHREATIC;

    public boolean isHydroVolcanic() {
        return this != VolcanoEruptCauseType.MAGMATIC;
    }
}
