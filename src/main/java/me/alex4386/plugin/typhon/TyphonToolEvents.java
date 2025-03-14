package me.alex4386.plugin.typhon;

import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoManager;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;

public class TyphonToolEvents implements Listener {
    public static Map<Player, Boolean> successorEnabled = new HashMap<>();
    boolean registered = false;

    @EventHandler
    public void onTyphonSuccessor(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (successorEnabled.containsKey(player)) {
            PlayerInventory inv = player.getInventory();
            ItemStack item = inv.getItemInMainHand();

            if (item.getType() == Material.WOODEN_SHOVEL) {
                // raycast to target
                Block targetBlock = player.getTargetBlockExact(100, FluidCollisionMode.NEVER);
                if (targetBlock != null) {
                    Volcano vol = VolcanoManager.getNearestVolcano(targetBlock.getLocation());
                    if (vol != null) {
                        vol.succession.runSuccession(targetBlock, true);
                        player.sendMessage("Succession has been run on the block you clicked.");
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    public void initialize() {
        if (!registered) {
            TyphonPlugin.plugin.getServer().getPluginManager().registerEvents(this, TyphonPlugin.plugin);
            registered = true;
        }
    }

    public void shutdown() {
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
    }

    public static void registerSuccessor(Player player) {
        successorEnabled.put(player, true);
    }

    public static void unregisterSuccessor(Player player) {
        successorEnabled.remove(player);
    }
}
