package me.alex4386.plugin.typhon.volcano.vent;

public enum VolcanoVentGenesis {
    MONOGENETIC("monogenetic", false),
    POLYGENETIC("polygenetic", true);

    String name;
    boolean canEruptAgain;

    VolcanoVentGenesis(String name, boolean canEruptAgain) {
        this.name = name;
        this.canEruptAgain = canEruptAgain;
    }

    public boolean canEruptAgain() {
        return this.canEruptAgain;
    }

    public static VolcanoVentGenesis getGenesisType(String name) {
        for (VolcanoVentGenesis genesis: VolcanoVentGenesis.values()) {
            if (genesis.name.equalsIgnoreCase(name)) {
                return genesis;
            }
        }

        return null;
    }

    public String getName() {
        return this.name;
    }
}
