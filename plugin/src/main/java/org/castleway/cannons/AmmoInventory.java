package org.castleway.cannons;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// cannon crate with powder and ammo slots
public final class AmmoInventory implements InventoryHolder {
    private static final Map<UUID, AmmoInventory> OPEN = new HashMap<>();

    public static final int SIZE = 27;
    public static final int MAX_POWDER = 20;
    public static final int MAX_FLAME = 5;
    public static final int MAX_SMOKE = 5;
    public static final int SPEED = 1, FLAME = 10, SMOKE = 19;
    public static final int HEALTH = 9, BUTTON = 13, READOUT = 17;
    static final int[] POWDER = {SPEED, FLAME, SMOKE};
    static final int[] POWDER_STORE = {2, 11, 20, 3, 12, 21};
    static final int[] AMMO_STORE = {5, 14, 23, 6, 15, 24};
    static final int[] AMMO = {7, 16, 25};

    private static final int SAFE_POWDER = 8;
    private static final double MISFIRE_AT_FULL = 0.10;

    private final Cannon cannon;
    private final Inventory inventory;

    public static AmmoInventory of(Cannon cannon) {
        AmmoInventory a = OPEN.get(cannon.id());
        if (a == null || !a.cannon.anchor().isValid()) {
            a = new AmmoInventory(cannon);
            OPEN.put(cannon.id(), a);
            WeaponHealth.register(cannon.anchor(), a.inventory, HEALTH, BUTTON);
            a.readout();
        }
        return a;
    }

    public static void dropAndForget(Cannon cannon) {
        AmmoInventory a = of(cannon);
        for (HumanEntity viewer : new ArrayList<>(a.inventory.getViewers())) viewer.closeInventory();
        for (int slot = 0; slot < SIZE; slot++) {
            if (!a.holds(slot)) continue;
            ItemStack s = a.inventory.getItem(slot);
            if (s != null && !s.getType().isAir()) {
                cannon.anchor().getWorld().dropItemNaturally(cannon.anchor().getLocation(), s);
            }
        }
        a.inventory.clear();
        OPEN.remove(cannon.id());
        WeaponHealth.forget(cannon.anchor());
    }

    private AmmoInventory(Cannon cannon) {
        this.cannon = cannon;
        this.inventory = Bukkit.createInventory(this, SIZE, title(cannon));
        load();
    }

    private static Component title(Cannon cannon) {
        if (!Cannon.plugin().customModels()) return Component.text(cannon.type().displayName);
        return Component.text("").font(Key.key("cannons", "gui")).color(NamedTextColor.WHITE);
    }

    @Override public Inventory getInventory() { return inventory; }
    public Cannon cannon() { return cannon; }

    private static boolean in(int[] slots, int slot) {
        for (int s : slots) if (s == slot) return true;
        return false;
    }

    private boolean holds(int slot) {
        return in(POWDER, slot) || in(POWDER_STORE, slot) || in(AMMO, slot) || in(AMMO_STORE, slot);
    }

    private static boolean powderSlot(int slot) {
        return in(POWDER, slot) || in(POWDER_STORE, slot);
    }

    private static boolean ammoSlot(int slot) {
        return in(AMMO, slot) || in(AMMO_STORE, slot);
    }

    private static boolean isPowder(ItemStack s) {
        return s != null && s.getType() == Material.GUNPOWDER;
    }

    private static boolean isAmmo(ItemStack s) {
        return s != null && s.getType().isBlock() && !s.getType().isAir();
    }

    private static boolean fits(int slot, ItemStack s) {
        if (s == null || s.getType().isAir()) return true;
        if (powderSlot(slot)) return isPowder(s);
        if (ammoSlot(slot)) return isAmmo(s);
        return false;
    }

    private int count(int[] slots) {
        int n = 0;
        for (int slot : slots) {
            ItemStack s = inventory.getItem(slot);
            if (isPowder(s)) n += s.getAmount();
        }
        return n;
    }

    public int powder() {
        return Math.min(MAX_POWDER, count(new int[]{SPEED}));
    }

    public int flame() {
        return Math.min(MAX_FLAME, count(new int[]{FLAME}));
    }

    public int smoke() {
        return Math.min(MAX_SMOKE, count(new int[]{SMOKE}));
    }

    public static double fireChance(int flame) {
        return 0.05 + 0.45 * Math.min(flame, MAX_FLAME) / MAX_FLAME;
    }

    public double misfireChance(int powder) {
        double p = MISFIRE_AT_FULL * Math.max(0, powder - SAFE_POWDER) / (double) (MAX_POWDER - SAFE_POWDER);
        if (cannon.type() == CannonType.MAKESHIFT) p *= 2;
        return Math.min(1.0, p);
    }

    public boolean rollMisfire(int powder) {
        return ThreadLocalRandom.current().nextDouble() < misfireChance(powder);
    }

    public Material peek() {
        top();
        for (int slot : AMMO) {
            ItemStack s = inventory.getItem(slot);
            if (isAmmo(s)) return s.getType();
        }
        return null;
    }

    public Material consume() {
        top();
        for (int slot : AMMO) {
            ItemStack s = inventory.getItem(slot);
            if (!isAmmo(s)) continue;
            Material m = s.getType();
            take(slot, 1);
            return m;
        }
        return null;
    }

    // burns the charge then refills from storage
    public void burn(int speed, int flame, int smoke) {
        burnSlot(SPEED, speed);
        burnSlot(FLAME, flame);
        burnSlot(SMOKE, smoke);
        top();
        save();
    }

    private void burnSlot(int slot, int amount) {
        if (amount <= 0) return;
        take(slot, amount);
        int want = amount - count(new int[]{slot});
        for (int from : POWDER_STORE) {
            if (want <= 0) break;
            ItemStack s = inventory.getItem(from);
            if (!isPowder(s)) continue;
            int n = Math.min(want, s.getAmount());
            ItemStack moved = s.clone();
            moved.setAmount(n);
            take(from, n);
            int back = put(new int[]{slot}, moved);
            if (back > 0) { moved.setAmount(back); put(POWDER_STORE, moved); }
            want -= n - back;
        }
    }

    private void top() {
        for (int slot : AMMO) if (isAmmo(inventory.getItem(slot))) return;
        for (int slot : AMMO_STORE) {
            ItemStack s = inventory.getItem(slot);
            if (!isAmmo(s)) continue;
            inventory.setItem(AMMO[0], s);
            inventory.setItem(slot, null);
            return;
        }
    }

    private void take(int slot, int n) {
        ItemStack s = inventory.getItem(slot);
        if (s == null) return;
        s.setAmount(s.getAmount() - n);
        inventory.setItem(slot, s.getAmount() <= 0 ? null : s);
    }

    private int put(int[] slots, ItemStack stack) {
        int left = stack.getAmount();
        for (int slot : slots) {
            if (left <= 0) break;
            ItemStack s = inventory.getItem(slot);
            if (s != null && s.isSimilar(stack) && s.getAmount() < s.getMaxStackSize()) {
                int n = Math.min(left, s.getMaxStackSize() - s.getAmount());
                s.setAmount(s.getAmount() + n);
                inventory.setItem(slot, s);
                left -= n;
            }
        }
        for (int slot : slots) {
            if (left <= 0) break;
            ItemStack s = inventory.getItem(slot);
            if (s == null || s.getType().isAir()) {
                ItemStack placed = stack.clone();
                placed.setAmount(Math.min(left, stack.getMaxStackSize()));
                inventory.setItem(slot, placed);
                left -= placed.getAmount();
            }
        }
        return left;
    }

    public void click(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        Player who = (Player) event.getWhoClicked();
        if (raw >= SIZE) {
            if (!event.isShiftClick()) return;
            event.setCancelled(true);
            ItemStack moving = event.getCurrentItem();
            if (moving == null || moving.getType().isAir()) return;
            int left = moving.getAmount();
            if (isPowder(moving)) {
                left = put(new int[]{SPEED}, moving);
                if (left > 0) { ItemStack rest = moving.clone(); rest.setAmount(left); left = put(POWDER_STORE, rest); }
            } else if (isAmmo(moving)) {
                left = put(AMMO, moving);
                if (left > 0) { ItemStack rest = moving.clone(); rest.setAmount(left); left = put(AMMO_STORE, rest); }
            }
            moving.setAmount(left);
            event.setCurrentItem(left <= 0 ? null : moving);
            changed();
            return;
        }
        if (raw == BUTTON) {
            event.setCancelled(true);
            if (who.hasPermission("cannons.use") && cannon.anchor().isValid()) {
                WeaponHealth.toggleDismantle(cannon.anchor(), who);
            }
            return;
        }
        if (!holds(raw)) { event.setCancelled(true); return; }
        ItemStack incoming = incoming(event, who);
        if (!fits(raw, incoming)) { event.setCancelled(true); return; }
        changed();
    }

    private static ItemStack incoming(InventoryClickEvent event, Player who) {
        InventoryAction a = event.getAction();
        return switch (a) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP -> event.getHotbarButton() >= 0
                    ? who.getInventory().getItem(event.getHotbarButton())
                    : who.getInventory().getItemInOffHand();
            default -> null;
        };
    }

    public void drag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw >= SIZE) continue;
            if (!holds(raw) || !fits(raw, event.getOldCursor())) {
                event.setCancelled(true);
                return;
            }
        }
        changed();
    }

    private void changed() {
        Bukkit.getScheduler().runTask(Cannon.plugin(), () -> {
            top();
            readout();
            save();
        });
    }

    public void readout() {
        int n = powder(), f = flame(), k = smoke();
        inventory.setItem(READOUT, WeaponHealth.button(Material.FIRE_CHARGE,
                Component.text("Speed " + n + " / " + MAX_POWDER + "  Flame " + f + " / " + MAX_FLAME
                        + "  Smoke " + k + " / " + MAX_SMOKE, NamedTextColor.GOLD),
                Component.text(String.format("speed %.0f%%  fire %.0f%%  misfire %.1f%%",
                        100.0 * n / MAX_POWDER, 100 * fireChance(f), 100 * misfireChance(n)), NamedTextColor.GRAY)));
    }

    public void save() {
        ItemStack[] out = new ItemStack[SIZE];
        for (int slot = 0; slot < SIZE; slot++) if (holds(slot)) out[slot] = inventory.getItem(slot);
        cannon.anchor().getPersistentDataContainer().set(Cannon.KEY_AMMO,
                PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(out));
    }

    private void load() {
        byte[] data = cannon.anchor().getPersistentDataContainer()
                .get(Cannon.KEY_AMMO, PersistentDataType.BYTE_ARRAY);
        if (data == null || data.length == 0) return;
        try {
            ItemStack[] saved = ItemStack.deserializeItemsFromBytes(data);
            for (int slot = 0; slot < Math.min(SIZE, saved.length); slot++) {
                if (holds(slot)) inventory.setItem(slot, saved[slot]);
            }
        } catch (Exception ignored) {
        }
    }
}
