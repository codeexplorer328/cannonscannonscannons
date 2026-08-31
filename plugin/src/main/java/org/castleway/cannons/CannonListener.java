package org.castleway.cannons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.HorseJumpEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

public final class CannonListener implements Listener {

    private final CannonsPlugin plugin;

    public CannonListener(CannonsPlugin plugin) { this.plugin = plugin; }

    // the muzzle opens the ammo rack and the body sits you down
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;

        String role = interaction.getPersistentDataContainer()
                .get(Cannon.KEY_ROLE, PersistentDataType.STRING);
        boolean isMuzzle = "muzzle".equals(role);
        if (!isMuzzle && !"anchor".equals(role)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("cannons.use")) return;

        Cannon cannon = isMuzzle ? Cannon.adoptFromPart(interaction) : Cannon.adopt(interaction);
        if (cannon == null) return;

        if (isMuzzle || player.isSneaking()) {
            player.openInventory(new AmmoInventory(cannon).getInventory());
            return;
        }

        // already seated so this click fires
        CannonSessions.Session existing = plugin.sessions().of(player);
        if (existing != null && existing.cannon().id().equals(cannon.id())
                && player.getVehicle() != null) {
            tryFire(player, existing);
            return;
        }

        cannon.mount(player);
        if (player.getVehicle() != null) plugin.sessions().begin(player, cannon);
    }

    // grab the jump power and cancel the actual jump
    @EventHandler
    public void onHorseJump(HorseJumpEvent event) {
        if (!"seat".equals(event.getEntity().getPersistentDataContainer()
                .get(Cannon.KEY_ROLE, PersistentDataType.STRING))) return;
        event.setCancelled(true);
        if (event.getEntity().getPassengers().isEmpty()) return;
        if (!(event.getEntity().getPassengers().get(0) instanceof Player player)) return;
        plugin.sessions().setCharge(player, event.getPower());
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        // this fires once per hand
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        CannonSessions.Session session = plugin.sessions().of(player);
        if (session == null) return;
        event.setCancelled(true);
        tryFire(player, session);
    }

    private void tryFire(Player player, CannonSessions.Session session) {
        AimState state = session.state();
        Material peek = session.ammo().peek();
        plugin.getLogger().info(String.format(
                "[fire] %s: charged=%s charge=%.2f ammo=%s elev=%.1f",
                player.getName(), state.charged, state.charge, peek, state.elevation));

        if (!state.charged) {
            player.sendActionBar(Component.text("not charged - hold space, then let go", NamedTextColor.RED));
            return;
        }
        Material shot = session.ammo().consume();
        if (shot == null) {
            player.sendActionBar(Component.text("rack is empty - press E to load", NamedTextColor.RED));
            return;
        }
        plugin.fire(session.cannon(), player, shot, state);
        plugin.sessions().afterFire(player);
    }

    // pressing E opens the horse inventory so swap in the ammo rack
    @EventHandler
    public void onOpenHorseInventory(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof Entity mount)) return;
        if (!"seat".equals(mount.getPersistentDataContainer()
                .get(Cannon.KEY_ROLE, PersistentDataType.STRING))) return;

        event.setCancelled(true);
        CannonSessions.Session session = plugin.sessions().of(player);
        if (session == null) return;
        // wait a tick for the cancel to go through
        plugin.getServer().getScheduler().runTask(plugin,
                () -> player.openInventory(session.ammo().getInventory()));
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (CannonSessions.isLanyard(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onLanyardClick(InventoryClickEvent event) {
        if (CannonSessions.isLanyard(event.getCurrentItem())
                || CannonSessions.isLanyard(event.getCursor())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (plugin.sessions().of(event.getPlayer()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().end(event.getPlayer());
    }

    @EventHandler
    public void onAmmoClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof AmmoInventory ammo) ammo.save();
    }

    @EventHandler
    public void onExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) plugin.sessions().end(player);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer()
                .get(Cannon.KEY_ROLE, PersistentDataType.STRING) != null) {
            event.setCancelled(true);
        }
    }
}
