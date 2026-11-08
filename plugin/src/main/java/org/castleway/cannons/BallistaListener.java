package org.castleway.cannons;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

// ballista mounting loading and firing
public final class BallistaListener implements Listener {
    private final CannonsPlugin plugin;

    public BallistaListener(CannonsPlugin plugin) { this.plugin = plugin; }

    private BallistaSessions sessions() { return plugin.ballistas(); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Interaction anchor = Ballista.anchorOf(event.getRightClicked());
        if (anchor == null) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!player.hasPermission("cannons.use")) return;
        Ballista b = sessions().get(anchor);
        if (b == null) return;

        if (player.isSneaking() && !b.isRider(player)) {
            player.openInventory(Ohmic_flicker_the_goat.of(b).getInventory());
            return;
        }
        if (holdingAmmo(player)) {
            tryLoad(player, b);
            return;
        }
        if (b.isRider(player)) return;
        b.mount(player);
        if (b.isRider(player)) sessions().begin(player, b);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Ballista b = sessions().ridden(player);
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (b == null || event.getHand() != EquipmentSlot.HAND || !holdingAmmo(player)) return;
        event.setCancelled(true);
        tryLoad(player, b);
    }

    // swap hands is sent even with empty hands
    @EventHandler(priority = EventPriority.HIGH)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Ballista b = sessions().ridden(event.getPlayer());
        if (b == null) return;
        event.setCancelled(true);
        tryFire(event.getPlayer(), b);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (Ballista.anchorOf(event.getAttacked()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) sessions().end(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (sessions().ridden(player) != null) player.leaveVehicle();
        sessions().end(player);
    }

    private void tryLoad(Player player, Ballista b) {
        if (!b.canLoad()) {
            player.sendActionBar(Component.text(
                    b.loaded() ? "already loaded" : "wait for it to finish", NamedTextColor.RED));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        b.load(hand);
        if (player.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
    }

    private void tryFire(Player player, Ballista b) {
        if (b.canFire()) {
            b.fire(player);
            return;
        }
        if (!b.loaded() && !b.busy()) {
            player.sendActionBar(Component.text("nothing loaded", NamedTextColor.RED));
        }
    }

    private static boolean holdingAmmo(Player player) {
        return Ballista.isAmmo(player.getInventory().getItemInMainHand());
    }
}
