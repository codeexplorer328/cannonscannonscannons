package org.castleway.cannons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// weapon health damage and disassembly
public final class WeaponHealth {
    public static NamespacedKey KEY_HEALTH, KEY_MENU_ITEM;


    private static final double BLAST_PER_POWER = 30.0;
    private static final float TNT_POWER = 4f;
    private static final float MISFIRE_POWER = 6f;

    private static CannonsPlugin plugin;
    private static final Map<UUID, UUID> dismantling = new HashMap<>();
    private static final Map<UUID, Inventory> menus = new HashMap<>();
    private static final Map<UUID, int[]> slots = new HashMap<>();
    private static final Set<UUID> dying = new HashSet<>();
    private static int ticks = 0;

    static void init(CannonsPlugin p) {
        plugin = p;
        KEY_HEALTH = new NamespacedKey(p, "weapon_health");
        KEY_MENU_ITEM = new NamespacedKey(p, "menu_item");
        new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(p, 1L, 1L);
    }

    public static boolean isWeapon(Entity e) {
        if (!(e instanceof Interaction)) return false;
        String role = e.getPersistentDataContainer().get(Cannon.KEY_ROLE, PersistentDataType.STRING);
        return "anchor".equals(role) || Ballista.ROLE.equals(role);
    }

    public static Interaction weaponOf(Entity part) {
        if (part == null) return null;
        if (isWeapon(part)) return (Interaction) part;
        String owner = part.getPersistentDataContainer().get(Cannon.KEY_OWNER, PersistentDataType.STRING);
        if (owner == null) return null;
        try {
            Entity e = part.getWorld().getEntity(UUID.fromString(owner));
            return isWeapon(e) ? (Interaction) e : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static String typeId(Interaction anchor) {
        return anchor.getPersistentDataContainer().get(Cannon.KEY_TYPE, PersistentDataType.STRING);
    }

    public static String displayName(Interaction anchor) {
        String id = typeId(anchor);
        BallistaType bt = BallistaType.byId(id);
        if (bt != null) return bt.displayName;
        CannonType ct = CannonType.byId(id);
        return ct != null ? ct.displayName : "Weapon";
    }

    public static double maxHealth(Interaction anchor) {
        String id = typeId(anchor);
        double fallback = switch (id == null ? "" : id) {
            case "cannon" -> 200;
            case "makeshift_cannon" -> 80;
            case "mounted_ballista" -> 140;
            default -> 100;
        };
        return Math.max(1, plugin.getConfig().getDouble("health." + id, fallback));
    }

    public static double health(Interaction anchor) {
        double max = maxHealth(anchor);
        double hp = anchor.getPersistentDataContainer().getOrDefault(KEY_HEALTH, PersistentDataType.DOUBLE, max);
        return Math.min(max, hp);
    }

    private static boolean wooden(Interaction anchor) {
        return !"cannon".equals(typeId(anchor));
    }

    public static void damage(Interaction anchor, double amount, Entity source) {
        if (amount <= 0 || anchor == null || !anchor.isValid() || dying.contains(anchor.getUniqueId())) return;
        double hp = health(anchor) - amount;
        anchor.getPersistentDataContainer().set(KEY_HEALTH, PersistentDataType.DOUBLE, Math.max(0, hp));

        World world = anchor.getWorld();
        Location mid = anchor.getLocation().add(0, anchor.getInteractionHeight() / 2, 0);
        if (wooden(anchor)) {
            world.playSound(mid, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.8f, 0.9f);
            world.spawnParticle(Particle.BLOCK, mid, 20, 0.6, 0.4, 0.6, 0.1, Material.SPRUCE_PLANKS.createBlockData());
        } else {
            world.playSound(mid, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.6f);
            world.spawnParticle(Particle.BLOCK, mid, 20, 0.6, 0.4, 0.6, 0.1, Material.IRON_BLOCK.createBlockData());
        }
        if (source instanceof Player p) {
            p.sendActionBar(Component.text(String.format("%s  %.0f / %.0f",
                    displayName(anchor), Math.max(0, hp), maxHealth(anchor)), NamedTextColor.RED));
        }
        refresh(anchor);
        if (hp <= 0) wreck(anchor);
    }

    public static void blast(Location at, float power, Entity source) {
        double reach = power * 2.0;
        for (Entity e : at.getWorld().getNearbyEntities(at, reach + 2, reach + 2, reach + 2)) {
            if (!isWeapon(e)) continue;
            Interaction a = (Interaction) e;
            Location mid = a.getLocation().add(0, a.getInteractionHeight() / 2, 0);
            double d = Math.max(0, mid.distance(at) - a.getInteractionWidth() / 2);
            if (d >= reach) continue;
            damage(a, BLAST_PER_POWER * power * (1 - d / reach), source);
        }
    }

    private static void wreck(Interaction anchor) {
        Location at = anchor.getLocation().add(0, 0.8, 0);
        World world = at.getWorld();
        takeDown(anchor);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, at, 40, 1.0, 0.6, 1.0, 0.05);
        world.spawnParticle(Particle.BLOCK, at, 80, 1.2, 0.6, 1.2, 0.2, Material.SPRUCE_PLANKS.createBlockData());
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.8f);
    }

    public static void misfire(Interaction anchor, Player who) {
        Location at = anchor.getLocation().add(0, 1, 0);
        takeDown(anchor);
        Foundation.explode(at, MISFIRE_POWER, Foundation.BASE_FIRE, true, who);
        blast(at, MISFIRE_POWER, who);
    }

    private static void detonate(Interaction anchor, UUID who) {
        Location at = anchor.getLocation().add(0, 0.5, 0);
        Player p = who != null ? plugin.getServer().getPlayer(who) : null;
        takeDown(anchor);
        Foundation.explode(at, TNT_POWER, Foundation.BASE_FIRE, true, p);
        blast(at, TNT_POWER, p);
    }

    private static final Set<UUID> collapsing = new HashSet<>();

    public static void collapse(UUID anchorId) {
        if (!collapsing.add(anchorId)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            collapsing.remove(anchorId);
            Entity e = plugin.getServer().getEntity(anchorId);
            if (e instanceof Interaction anchor && anchor.isValid() && isWeapon(anchor)) detonate(anchor, null);
        });
    }

    private static void takeDown(Interaction anchor) {
        UUID id = anchor.getUniqueId();
        dying.add(id);
        dismantling.remove(id);
        Inventory menu = menus.remove(id);
        if (menu != null) for (var viewer : List.copyOf(menu.getViewers())) viewer.closeInventory();
        if (Ballista.isBallista(anchor)) {
            Ballista b = plugin.ballistas().get(anchor);
            if (b != null) { plugin.ballistas().forget(b); b.remove(); }
        } else {
            Cannon c = Cannon.adopt(anchor);
            if (c != null) c.remove();
        }
        if (anchor.isValid()) anchor.remove();
        dying.remove(id);
    }

    public static boolean dismantling(Interaction anchor) {
        return dismantling.containsKey(anchor.getUniqueId());
    }

    public static void toggleDismantle(Interaction anchor, Player who) {
        UUID id = anchor.getUniqueId();
        if (dismantling.remove(id) != null) {
            anchor.getWorld().playSound(anchor.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f);
        } else {
            dismantling.put(id, who.getUniqueId());
            anchor.getWorld().playSound(anchor.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 0.8f);
        }
        refresh(anchor);
    }

    private static void tick() {
        ticks++;
        double seconds = Math.max(1, plugin.getConfig().getDouble("disassemble-seconds", 15));
        Map<Interaction, UUID> done = new HashMap<>();
        Iterator<Map.Entry<UUID, UUID>> it = dismantling.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> job = it.next();
            Entity e = plugin.getServer().getEntity(job.getKey());
            if (!(e instanceof Interaction anchor) || !anchor.isValid()) { it.remove(); continue; }
            double hp = health(anchor) - maxHealth(anchor) / (seconds * 20);
            anchor.getPersistentDataContainer().set(KEY_HEALTH, PersistentDataType.DOUBLE, Math.max(0, hp));
            Location mid = anchor.getLocation().add(0, anchor.getInteractionHeight() / 2, 0);
            if (ticks % 4 == 0) anchor.getWorld().spawnParticle(Particle.SMOKE, mid, 6, 0.6, 0.4, 0.6, 0.02);
            if (ticks % 20 == 0) anchor.getWorld().playSound(mid, Sound.ENTITY_CREEPER_PRIMED, 0.8f, 0.7f);
            if (ticks % 5 == 0) refresh(anchor);
            if (hp <= 0) {
                it.remove();
                done.put(anchor, job.getValue());
            }
        }
        // a blast can wreck weapons still in the map
        done.forEach(WeaponHealth::detonate);
    }

    public static void register(Interaction anchor, Inventory menu, int healthSlot, int buttonSlot) {
        menus.put(anchor.getUniqueId(), menu);
        slots.put(anchor.getUniqueId(), new int[]{healthSlot, buttonSlot});
        decorate(menu, anchor);
    }

    public static void forget(Interaction anchor) {
        menus.remove(anchor.getUniqueId());
        slots.remove(anchor.getUniqueId());
    }

    public static void refresh(Interaction anchor) {
        Inventory menu = menus.get(anchor.getUniqueId());
        if (menu != null) decorate(menu, anchor);
    }

    public static void decorate(Inventory menu, Interaction anchor) {
        int[] at = slots.get(anchor.getUniqueId());
        if (at == null) return;
        double hp = health(anchor), max = maxHealth(anchor);
        int bars = (int) Math.round(20 * hp / max);
        String bar = "|".repeat(bars) + ".".repeat(20 - bars);
        menu.setItem(at[0], button(Material.REDSTONE,
                Component.text(String.format("Health %.0f / %.0f", hp, max), NamedTextColor.RED),
                Component.text(bar, hp / max > 0.3 ? NamedTextColor.GREEN : NamedTextColor.RED)));
        if (dismantling(anchor)) {
            menu.setItem(at[1], button(Material.BARRIER,
                    Component.text("Stop disassembling", NamedTextColor.YELLOW),
                    Component.text("it blows up like tnt when health hits zero", NamedTextColor.GRAY)));
        } else {
            menu.setItem(at[1], button(Material.TNT,
                    Component.text("Disassemble", NamedTextColor.RED),
                    Component.text("slowly takes it apart then it blows up like tnt", NamedTextColor.GRAY)));
        }
    }

    public static ItemStack button(Material m, Component name, Component lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore != null) meta.lore(List.of(lore.decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(KEY_MENU_ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack filler() {
        return button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
    }

    public static boolean isMenuItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(KEY_MENU_ITEM, PersistentDataType.BYTE);
    }

    public static String hud(Interaction anchor) {
        String s = String.format("hp %.0f/%.0f", health(anchor), maxHealth(anchor));
        return dismantling(anchor) ? s + "  disassembling" : s;
    }
}
