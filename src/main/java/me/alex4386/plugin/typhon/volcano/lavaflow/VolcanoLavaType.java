package me.alex4386.plugin.typhon.volcano.lavaflow;

public enum VolcanoLavaType {
    NORMAL, // Real LAVA blocks, Minecraft physics
    LITE,   // MAGMA_BLOCK, Typhon-managed, surface (no sky access, no player nearby)
    PILLOW  // MAGMA_BLOCK, Typhon-managed, underwater
}
