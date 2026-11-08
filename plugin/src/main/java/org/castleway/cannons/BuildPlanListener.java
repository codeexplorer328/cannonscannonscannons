package org.castleway.cannons;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// placing plans and building or packing up zones
public final class BuildPlanListener implements Listener {
    private final CannonsPlugin plugin;
    private final Map<UUID, Integer> lastPlaced = new HashMap<>();

    public BuildPlanListener(CannonsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        ItemStack held = event.getItem();
        BuildPlan.Kind kind = BuildPlan.kindOf(held);
        if (kind == null) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.RIGHT_CLICK_AIR) return;
        event.setCancelled(true);
        if (a != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && BuildPlan.kindOf(event.getPlayer().getInventory().getItemInMainHand()) != null) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("cannons.use")) return;
        Block spot = event.getClickedBlock().getRelative(event.getBlockFace());
        if (!BuildPlan.fits(spot)) {
            player.sendActionBar(Component.text("needs a clear 3 by 3 space on a full 3 by 3 of solid ground", NamedTextColor.RED));
            return;
        }
        int tick = plugin.getServer().getCurrentTick();
        Integer last = lastPlaced.put(player.getUniqueId(), tick);
        if (last != null && last == tick) return;
        float yaw = Math.round(player.getLocation().getYaw() / 90f) * 90f;
        BuildPlan.place(kind, spot, yaw, player.getUniqueId());
        if (player.getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEntityEvent event) {
        Interaction zone = BuildPlan.zoneOf(event.getRightClicked());
        if (zone == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getPlayer().hasPermission("cannons.use") || !zone.isValid()) return;
        BuildPlan.build(zone, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(PrePlayerAttackEntityEvent event) {
        Interaction zone = BuildPlan.zoneOf(event.getAttacked());
        if (zone == null) return;
        event.setCancelled(true);
        if (!event.getPlayer().hasPermission("cannons.use") || !zone.isValid()) return;
        BuildPlan.packUp(zone, event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipes(BuildPlan.recipes());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPlaced.remove(event.getPlayer().getUniqueId());
    }
}
