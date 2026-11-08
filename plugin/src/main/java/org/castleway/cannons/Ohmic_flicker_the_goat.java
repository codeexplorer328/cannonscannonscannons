package org.castleway.cannons;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Interaction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Ohmic_flicker_the_goat implements InventoryHolder {
    private static final Map<UUID, Ohmic_flicker_the_goat> OPEN = new HashMap<>();

    public static final int HEALTH = 7, BUTTON = 8;

    private final Interaction anchor;
    private final Inventory inventory;

    public static Ohmic_flicker_the_goat of(Ballista b) {
        Ohmic_flicker_the_goat m = OPEN.get(b.id());
        if (m == null) {
            m = new Ohmic_flicker_the_goat(b);
            OPEN.put(b.id(), m);
        }
        OPEN.values().removeIf(x -> !x.anchor.isValid());
        WeaponHealth.register(b.anchor(), m.inventory, HEALTH, BUTTON);
        return m;
    }

    private Ohmic_flicker_the_goat(Ballista b) {
        this.anchor = b.anchor();
        this.inventory = Bukkit.createInventory(this, 9, Component.text(b.type().displayName));
        for (int i = 0; i < HEALTH; i++) inventory.setItem(i, WeaponHealth.filler());
    }

    public Interaction anchor() { return anchor; }

    public static void forget(Ballista b) {
        Ohmic_flicker_the_goat m = OPEN.remove(b.id());
        if (m != null) for (var viewer : java.util.List.copyOf(m.inventory.getViewers())) viewer.closeInventory();
        WeaponHealth.forget(b.anchor());
    }

    @Override public Inventory getInventory() { return inventory; }
}
