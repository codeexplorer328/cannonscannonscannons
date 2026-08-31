package org.castleway.cannons;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

// 5 slot hopper that fires left to right
public final class AmmoInventory implements InventoryHolder {

    private final Cannon cannon;
    private final Inventory inventory;

    public AmmoInventory(Cannon cannon) {
        this.cannon = cannon;
        this.inventory = Bukkit.createInventory(this, InventoryType.HOPPER,
                Component.text(cannon.type().displayName + " - ammo"));
        load();
    }

    @Override public Inventory getInventory() { return inventory; }
    public Cannon cannon() { return cannon; }

    public Material peek() {
        for (ItemStack s : inventory.getContents()) {
            if (s != null && s.getType().isBlock() && !s.getType().isAir()) return s.getType();
        }
        return null;
    }

    public Material consume() {
        ItemStack[] c = inventory.getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack s = c[i];
            if (s == null || !s.getType().isBlock() || s.getType().isAir()) continue;
            Material m = s.getType();
            s.setAmount(s.getAmount() - 1);
            inventory.setItem(i, s.getAmount() <= 0 ? null : s);
            save();
            return m;
        }
        return null;
    }

    public void save() {
        cannon.anchor().getPersistentDataContainer().set(Cannon.KEY_AMMO,
                PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(inventory.getContents()));
    }

    private void load() {
        byte[] data = cannon.anchor().getPersistentDataContainer()
                .get(Cannon.KEY_AMMO, PersistentDataType.BYTE_ARRAY);
        if (data == null || data.length == 0) return;
        try {
            inventory.setContents(ItemStack.deserializeItemsFromBytes(data));
        } catch (Exception ignored) {
            // old save format so just start empty
        }
    }
}
