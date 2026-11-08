package org.castleway.cannons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.HorseJumpEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

// cannon mounting crate opening and firing
public final class CannonListener implements Listener {
    private final CannonsPlugin plugin;

    public CannonListener(CannonsPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onSeatClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Horse horse)) return;
        if (!"seat".equals(horse.getPersistentDataContainer()
                .get(Cannon.KEY_ROLE, PersistentDataType.STRING))) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("cannons.use") || player.getVehicle() != null) return;
        Cannon cannon = Cannon.adoptFromPart(horse);
        if (cannon == null) return;
        cannon.mount(player);
        if (player.getVehicle() != null) plugin.sessions().begin(player, cannon);
    }

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
            player.openInventory(AmmoInventory.of(cannon).getInventory());
            return;
        }

        CannonSessions.Session existing = plugin.sessions().of(player);
        if (existing != null && existing.cannon().id().equals(cannon.id())
                && player.getVehicle() != null) return;

        cannon.mount(player);
        if (player.getVehicle() != null) plugin.sessions().begin(player, cannon);
    }

    @EventHandler
    public void onHorseJump(HorseJumpEvent event) {
        if (!"seat".equals(event.getEntity().getPersistentDataContainer()
                .get(Cannon.KEY_ROLE, PersistentDataType.STRING))) return;
        event.setCancelled(true);
    }

    private void tryFire(Player player, CannonSessions.Session session) {
        AimState state = session.state();
        AmmoInventory rack = session.ammo();
        int powder = rack.powder();
        Material peek = rack.peek();
        plugin.getLogger().info(String.format("[fire] %s: powder=%d ammo=%s elev=%.1f",
                player.getName(), powder, peek, state.elevation));

        if (powder <= 0) {
            player.sendActionBar(Component.text("no gunpowder - press E to load", NamedTextColor.RED));
            return;
        }
        if (peek == null) {
            player.sendActionBar(Component.text("no ammo - press E to load", NamedTextColor.RED));
            return;
        }
        if (rack.rollMisfire(powder)) {
            WeaponHealth.misfire(session.cannon().anchor(), player);
            return;
        }
        int flame = rack.flame();
        int smoke = rack.smoke();
        Material shot = rack.consume();
        rack.burn(powder, flame, smoke);
        rack.readout();
        plugin.fire(session.cannon(), player, shot, powder, flame, smoke, state.elevation);
    }

    @EventHandler
    public void onOpenHorseInventory(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof Entity mount)) return;
        if (!"seat".equals(mount.getPersistentDataContainer()
                .get(Cannon.KEY_ROLE, PersistentDataType.STRING))) return;

        event.setCancelled(true);
        CannonSessions.Session session = plugin.sessions().of(player);
        if (session == null) return;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> player.openInventory(session.ammo().getInventory()));
    }

    @EventHandler
    // swap hands is sent even with empty hands
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        CannonSessions.Session session = plugin.sessions().of(event.getPlayer());
        if (session == null) return;
        event.setCancelled(true);
        tryFire(event.getPlayer(), session);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().end(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        plugin.sessions().end(event.getEntity());
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
