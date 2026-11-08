package org.castleway.cannons;

import org.bukkit.entity.Firework;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;

// menu clicks and outside explosions
public final class WeaponListener implements Listener {
    private static Interaction anchorOf(InventoryHolder holder) {
        if (holder instanceof AmmoInventory ammo) return ammo.cannon().anchor();
        if (holder instanceof Ohmic_flicker_the_goat menu) return menu.anchor();
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof AmmoInventory rack) {
            if (WeaponHealth.isMenuItem(event.getCurrentItem()) || WeaponHealth.isMenuItem(event.getCursor())) {
                event.setCancelled(true);
            }
            if (!event.isCancelled() || event.getRawSlot() == AmmoInventory.BUTTON) rack.click(event);
            return;
        }
        Interaction anchor = anchorOf(holder);
        if (anchor == null) return;
        int top = event.getView().getTopInventory().getSize();
        int slot = event.getRawSlot();
        if (slot < top || event.isShiftClick()) event.setCancelled(true);
        if (WeaponHealth.isMenuItem(event.getCurrentItem()) || WeaponHealth.isMenuItem(event.getCursor())) {
            event.setCancelled(true);
        }

        if (slot == Ohmic_flicker_the_goat.BUTTON && event.getWhoClicked() instanceof Player p
                && p.hasPermission("cannons.use") && anchor.isValid()) {
            WeaponHealth.toggleDismantle(anchor, p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof AmmoInventory rack) {
            rack.drag(event);
            return;
        }
        Interaction anchor = anchorOf(holder);
        if (anchor == null) return;
        int top = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < top) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        Foundation.primed(event.getEntity(), event.getRadius());
        WeaponHealth.blast(event.getEntity().getLocation(), event.getRadius(), event.getEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFireworkHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework f
                && f.getPersistentDataContainer().has(BallistaRocket.KEY_SHOW, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}
