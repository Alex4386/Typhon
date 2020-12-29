package me.alex4386.plugin.typhon;

import net.minecraft.server.v1_16_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_16_R3.CraftChunk;
import org.bukkit.craftbukkit.v1_16_R3.CraftParticle;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.util.CraftMagicNumbers;
import org.json.simple.JSONObject;
import org.spigotmc.AsyncCatcher;

import java.util.ArrayList;
import java.util.List;

public class TyphonNMSUtils {
    //Package nmsPackage = Package.getPackage("org.bukkit.craftbukkit.v1_16_R3");

    public static void setBlockMaterial(org.bukkit.block.Block block, org.bukkit.Material material) {
        setBlockMaterial(block, material, true);
    }

    public static void setBlockMaterial(org.bukkit.block.Block block, org.bukkit.Material material, boolean applyPhysics) {
        setLocationMaterial(block.getLocation(), material, applyPhysics, true);
    }

    public static void setBlockMaterial(org.bukkit.block.Block block, org.bukkit.Material material, boolean applyPhysics, boolean updateChunk) {
        setLocationMaterial(block.getLocation(), material, applyPhysics, updateChunk);
    }

    public static void setLocationMaterial(org.bukkit.Location location, org.bukkit.Material material) {
        setLocationMaterial(location, material, true);
    }

    public static void setLocationMaterial(org.bukkit.Location location, org.bukkit.Material material, boolean applyPhysics) {
        setLocationMaterial(location, material, applyPhysics, true);
    }

    public static void setLocationMaterial(org.bukkit.Location location, org.bukkit.Material material, boolean applyPhysics, boolean updateChunk) {
        WorldServer world = ((CraftWorld) location.getWorld()).getHandle();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);

        BlockPosition blockPosition = new BlockPosition(x,y,z);

        Block tmpBlock = CraftMagicNumbers.getBlock(material);
        IBlockData iBlockData = tmpBlock.getBlockData();

        chunk.setType(blockPosition, iBlockData, applyPhysics);

        if (updateChunk) updateChunk(location);
    }

    public static void copyBlock(org.bukkit.block.Block block, org.bukkit.block.Block toBlock) {
        copyBlock(block, toBlock, true);
    }

    public static void copyBlock(org.bukkit.block.Block block, org.bukkit.block.Block toBlock, boolean applyPhysics) {
        copyBlock(block, toBlock, applyPhysics, true);
    }

    public static void copyBlock(org.bukkit.block.Block block, org.bukkit.block.Block toBlock, boolean applyPhysics, boolean updateChunk) {
        WorldServer world = ((CraftWorld) block.getWorld()).getHandle();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        int toX = toBlock.getX();
        int toY = toBlock.getY();
        int toZ = toBlock.getZ();

        BlockPosition bp = new BlockPosition(x, y, z);
        IBlockData blockData = world.getType(bp);

        BlockPosition toBP = new BlockPosition(toX, toY, toZ);
        Chunk toChunk = world.getChunkAt(toX >> 4, toZ >> 4);

        toChunk.setType(toBP, blockData, applyPhysics);

        if (updateChunk) {
            updateChunk(block);
            updateChunk(toBlock);
        }
    }

    public static void moveBlock(org.bukkit.block.Block block, org.bukkit.block.Block toBlock) {
        moveBlock(block, toBlock, true);
    }

    public static void moveBlock(org.bukkit.block.Block block, org.bukkit.block.Block toBlock, boolean applyPhysics) {
        moveBlock(block, toBlock, applyPhysics, true);
    }

    public static void moveBlock(org.bukkit.block.Block block, org.bukkit.block.Block toBlock, boolean applyPhysics, boolean updateChunk) {
        copyBlock(block, toBlock, applyPhysics, updateChunk);
        setBlockMaterial(block, org.bukkit.Material.AIR, applyPhysics, updateChunk);
    }

    public static void updateChunk(org.bukkit.block.Block block) {
        org.bukkit.Chunk chunk = block.getChunk();
        updateChunk(chunk);
    }

    public static void updateChunk(org.bukkit.Location loc) {
        org.bukkit.Chunk chunk = loc.getChunk();
        updateChunk(chunk);
    }

    public static void updateChunk(org.bukkit.Chunk chunk) {
        AsyncCatcher.catchOp("chunk update");

        int diffx, diffz;
        int view = Bukkit.getServer().getViewDistance() << 4;

        Chunk rawChunk = ((CraftChunk) chunk).getHandle();
        WorldServer world = rawChunk.world;

        // force server to reload the chunk.
        boolean previouslyForceLoaded = chunk.isForceLoaded();
        chunk.setForceLoaded(false);
        chunk.unload();

        chunk.load();
        chunk.setForceLoaded(previouslyForceLoaded);

        // request all client to update chunk data
        for (EntityPlayer ep : (List<EntityPlayer>) world.getPlayers()) {
            diffx = Math.abs((int) ep.locX() - chunk.getX() << 4);
            diffz = Math.abs((int) ep.locZ() - chunk.getZ() << 4);

            if (diffx <= view && diffz <= view) {
                ep.a(new ChunkCoordIntPair(chunk.getX(), chunk.getZ()));

                // every single block in chunk has been updated.
                ep.playerConnection.sendPacket(new PacketPlayOutMapChunk(
                        rawChunk,
                        65535
//                        , false
                ));

            }
        }
    }

    public static <T> void createParticle(org.bukkit.Particle particle, org.bukkit.Location loc, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
        ParticleParam particleParam = CraftParticle.toNMS(particle, data);

        PacketPlayOutWorldParticles packet = new PacketPlayOutWorldParticles(particleParam, true,
                (float) (loc.getX()), (float) (loc.getY()), (float) (loc.getZ()), (float) offsetX, (float) offsetY, (float) offsetZ, (float) extra, count);
        org.bukkit.World bukkitWorld = loc.getWorld();
        WorldServer world = ((CraftWorld) bukkitWorld).getHandle();

        for (EntityPlayer ep : world.getPlayers()) {
            ep.playerConnection.sendPacket(packet);
        }
    }

    public static void createParticle(org.bukkit.Particle particle, org.bukkit.Location loc, int count, double offsetX, double offsetY, double offsetZ, double extra) {
        createParticle(particle, loc, count, offsetX, offsetY, offsetZ, extra, (Object)null);
    }

    public static void createParticle(org.bukkit.Particle particle, org.bukkit.Location loc, int count, double offsetX, double offsetY, double offsetZ) {
        createParticle(particle, loc, count, offsetX, offsetY, offsetZ, 0);
    }

    public static void createParticle(org.bukkit.Particle particle, org.bukkit.Location loc, int count) {
        createParticle(particle, loc, count, 0,0,0);
    }
}
