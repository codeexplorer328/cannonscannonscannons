package org.castleway.cannons;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// plan items recipes and the building zone
public final class BuildPlan {
    public static final String ROLE = "build_zone";

    public record Kind(String id, String displayName, Material top, Material planks, Material wool) {
        public String model() { return "cannons:build_plan_" + id; }
        public String desk()  { return "cannons:build_zone_desk_" + id; }
    }

    public static final List<Kind> KINDS = List.of(
            new Kind("cannon", "Heavy Cannon", Material.COMPASS, Material.BIRCH_PLANKS, Material.MAGENTA_WOOL),
            new Kind("makeshift_cannon", "Makeshift Cannon", Material.CRAFTING_TABLE, Material.CRIMSON_PLANKS, Material.BLUE_WOOL),
            new Kind("ballista", "Ballista", Material.BOW, Material.SPRUCE_PLANKS, Material.RED_WOOL),
            new Kind("mounted_ballista", "Mounted Ballista", Material.CROSSBOW, Material.DARK_OAK_PLANKS, Material.GREEN_WOOL));

    private static final Sound[] HAMMER = {
            Sound.BLOCK_ANVIL_USE, Sound.BLOCK_ANVIL_PLACE, Sound.BLOCK_ANVIL_LAND, Sound.BLOCK_ANVIL_HIT};
    private static final float ZONE_WIDTH = 3f;
    private static final float ZONE_HEIGHT = 1.6f;
    private static final String DESK_PIECE = "bone2";

    public static NamespacedKey KEY_PLAN;
    private static final List<NamespacedKey> recipes = new ArrayList<>();
    private static CannonsPlugin plugin;
    private static BallistaRig rig;

    static void init(CannonsPlugin p) {
        plugin = p;
        KEY_PLAN = new NamespacedKey(p, "build_plan");
        rig = BallistaRig.load(p, "build_zone.json", "", "bone", "bone", "bone");
        for (Kind k : KINDS) {
            NamespacedKey key = new NamespacedKey(p, "plan_" + k.id());
            ShapedRecipe r = new ShapedRecipe(key, item(k));
            r.shape("  T", "PAF", "WDI");
            r.setIngredient('T', k.top());
            r.setIngredient('P', k.planks());
            r.setIngredient('A', Material.PAPER);
            r.setIngredient('F', Material.FEATHER);
            r.setIngredient('W', k.wool());
            r.setIngredient('D', Material.LIGHT_BLUE_DYE);
            r.setIngredient('I', Material.INK_SAC);
            p.getServer().removeRecipe(key);
            p.getServer().addRecipe(r);
            recipes.add(key);
        }
    }

    public static List<NamespacedKey> recipes() { return recipes; }

    public static Kind byId(String id) {
        for (Kind k : KINDS) if (k.id().equals(id)) return k;
        return null;
    }

    // trial keys have no recipes or trades
    public static ItemStack item(Kind kind) {
        ItemStack stack = new ItemStack(Material.TRIAL_KEY);
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(kind.model()));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(kind.displayName() + " Build Plan", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("place it on the ground then right click it", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("hit it to pack it back up", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(KEY_PLAN, PersistentDataType.STRING, kind.id());
        stack.setItemMeta(meta);
        return stack;
    }

    public static Kind kindOf(ItemStack stack) {
        if (stack == null || stack.getType() != Material.TRIAL_KEY || !stack.hasItemMeta()) return null;
        return byId(stack.getItemMeta().getPersistentDataContainer().get(KEY_PLAN, PersistentDataType.STRING));
    }

    public static boolean fits(Block spot) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (!spot.getRelative(dx, dy, dz).isPassable()) return false;
                }
            }
        }
        if (!Foundation.complete(spot)) return false;
        Location c = spot.getLocation().add(0.5, 0, 0.5);
        for (Entity e : spot.getWorld().getNearbyEntities(c, 1.4, 1.5, 1.4)) {
            if (!(e instanceof Interaction)) continue;
            if (e.getPersistentDataContainer().has(Cannon.KEY_ROLE, PersistentDataType.STRING)) return false;
        }
        return true;
    }

    public static Interaction place(Kind kind, Block spot, float yaw, UUID owner) {
        Location base = spot.getLocation().add(0.5, 0, 0.5);
        World world = base.getWorld();
        Interaction anchor = world.spawn(base, Interaction.class, e -> {
            e.setInteractionWidth(ZONE_WIDTH);
            e.setInteractionHeight(ZONE_HEIGHT);
            e.setResponsive(true);
            e.setPersistent(true);
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(Cannon.KEY_ROLE, PersistentDataType.STRING, ROLE);
            pdc.set(KEY_PLAN, PersistentDataType.STRING, kind.id());
            pdc.set(Cannon.KEY_YAW, PersistentDataType.FLOAT, yaw);
            if (owner != null) pdc.set(Cannon.KEY_OWNER, PersistentDataType.STRING, owner.toString());
        });

        Matrix4f[] world0 = rig.pose(0f, 0f, 0f);
        float worldYaw = (float) Math.toRadians(180f - yaw);
        for (int i = 0; i < rig.pieces.size(); i++) {
            BallistaRig.Piece p = rig.pieces.get(i);
            ItemStack stack;
            if (plugin.customModels()) {
                stack = new ItemStack(Material.PAPER);
                String model = DESK_PIECE.equals(p.name()) ? kind.desk() : p.model();
                stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
            } else {
                stack = new ItemStack(plugin.debugBlock(i));
            }
            Matrix4f m = rig.pieceMatrix(p, world0, worldYaw);
            Location at = base.clone();
            world.spawn(at, ItemDisplay.class, e -> {
                e.setItemStack(stack);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setPersistent(true);
                e.setViewRange(2.5f);
                e.setTransformationMatrix(m);
                PersistentDataContainer pdc = e.getPersistentDataContainer();
                pdc.set(Cannon.KEY_ROLE, PersistentDataType.STRING, "display");
                pdc.set(Cannon.KEY_BONE, PersistentDataType.STRING, p.name());
                pdc.set(Cannon.KEY_OWNER, PersistentDataType.STRING, anchor.getUniqueId().toString());
            });
        }
        world.playSound(base, Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
        world.playSound(base, Sound.BLOCK_WOOD_PLACE, 1f, 0.8f);
        return anchor;
    }

    public static boolean isZone(Entity e) {
        return e instanceof Interaction && ROLE.equals(
                e.getPersistentDataContainer().get(Cannon.KEY_ROLE, PersistentDataType.STRING));
    }

    public static Interaction zoneOf(Entity part) {
        if (part == null) return null;
        if (isZone(part)) return (Interaction) part;
        String owner = part.getPersistentDataContainer().get(Cannon.KEY_OWNER, PersistentDataType.STRING);
        if (owner == null) return null;
        try {
            Entity e = part.getWorld().getEntity(UUID.fromString(owner));
            return isZone(e) ? (Interaction) e : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static Kind kindOf(Interaction zone) {
        return byId(zone.getPersistentDataContainer().get(KEY_PLAN, PersistentDataType.STRING));
    }

    public static void build(Interaction zone, Player who) {
        Kind kind = kindOf(zone);
        if (kind == null) return;
        if (!Foundation.complete(zone.getLocation().getBlock())) {
            if (who != null) who.sendActionBar(Component.text("the ground under it needs to be a full 3 by 3 of solid blocks", NamedTextColor.RED));
            return;
        }
        Location at = zone.getLocation();
        float yaw = zone.getPersistentDataContainer().getOrDefault(Cannon.KEY_YAW, PersistentDataType.FLOAT, 0f);
        UUID owner = who != null ? who.getUniqueId() : null;
        clear(zone);

        World world = at.getWorld();
        Location mid = at.clone().add(0, 0.8, 0);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, mid, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.EXPLOSION, mid, 6, 1.0, 0.5, 1.0, 0);
        world.spawnParticle(Particle.CLOUD, mid, 60, 1.2, 0.6, 1.2, 0.08);
        world.spawnParticle(Particle.LARGE_SMOKE, mid, 30, 1.0, 0.6, 1.0, 0.03);
        world.spawnParticle(Particle.BLOCK, mid, 80, 1.2, 0.6, 1.2, 0.2, Material.SPRUCE_PLANKS.createBlockData());
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 1.1f);
        Sound hammer = HAMMER[ThreadLocalRandom.current().nextInt(HAMMER.length)];
        world.playSound(at, hammer, 0.8f, 0.6f + ThreadLocalRandom.current().nextFloat() * 0.5f);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            BallistaType bt = BallistaType.byId(kind.id());
            if (bt != null) {
                plugin.ballistas().track(Ballista.spawn(bt, at, yaw, owner));
                return;
            }
            CannonType ct = CannonType.byId(kind.id());
            if (ct != null) Cannon.spawn(ct, at, yaw, owner);
        });
    }

    public static void packUp(Interaction zone, Player who) {
        Kind kind = kindOf(zone);
        Location at = zone.getLocation();
        clear(zone);
        at.getWorld().playSound(at, Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
        if (kind == null) return;
        ItemStack plan = item(kind);
        if (who != null && who.isOnline()) {
            who.getInventory().addItem(plan).values()
                    .forEach(left -> who.getWorld().dropItemNaturally(who.getLocation(), left));
        } else {
            at.getWorld().dropItemNaturally(at.clone().add(0, 0.5, 0), plan);
        }
    }

    public static void clear(Interaction zone) {
        String self = zone.getUniqueId().toString();
        for (Entity e : zone.getWorld().getNearbyEntities(zone.getLocation(), 4, 4, 4)) {
            if (self.equals(e.getPersistentDataContainer().get(Cannon.KEY_OWNER, PersistentDataType.STRING))) {
                e.remove();
            }
        }
        zone.remove();
    }
}
