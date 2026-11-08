package org.castleway.cannons;

import org.bukkit.block.Block;
import org.bukkit.entity.Enderman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;
import java.util.UUID;

// sets a weapon off when its foundation breaks
public final class FoundationListener implements Listener {
    private static final float BLOCK_EXPLOSION_POWER = 5f;
    private static final float ENTITY_EXPLOSION_POWER = 4f;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        float power = Foundation.powerOf(event.getEntity(), ENTITY_EXPLOSION_POWER);
        Foundation.sift(event.blockList(), event.getLocation(), power).forEach(WeaponHealth::collapse);
        Foundation.ignite(event.blockList(), Foundation.fireChance());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        float power = Foundation.powerOf(null, BLOCK_EXPLOSION_POWER);
        var center = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        Foundation.sift(event.blockList(), center, power).forEach(WeaponHealth::collapse);
        Foundation.ignite(event.blockList(), Foundation.fireChance());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        trip(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        trip(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent event) {
        if (Foundation.ownerOf(event.getBlock()) == null) return;
        if (event.getEntity() instanceof Enderman) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChanged(EntityChangeBlockEvent event) {
        trip(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent event) {
        if (anyFoundation(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent event) {
        if (anyFoundation(event.getBlocks())) event.setCancelled(true);
    }

    private static boolean anyFoundation(List<Block> blocks) {
        for (Block b : blocks) if (Foundation.ownerOf(b) != null) return true;
        return false;
    }

    private static void trip(Block b) {
        UUID owner = Foundation.ownerOf(b);
        if (owner != null) WeaponHealth.collapse(owner);
    }
}
