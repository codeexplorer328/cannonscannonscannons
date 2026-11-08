package org.castleway.cannons;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// blast resistant ground under each weapon
public final class Foundation {
    public static final float BLAST_RESISTANCE = 6.0f;
    public static final double BASE_FIRE = 1.0 / 3.0;

    private static NamespacedKey KEY_CHUNK, KEY_BLOCKS;
    private static CannonsPlugin plugin;
    private static final Map<String, Map<String, UUID>> cache = new HashMap<>();
    private static float pendingPower = Float.NaN;
    private static double pendingFire = 0;
    private static final Map<UUID, Float> primed = new HashMap<>();

    static void init(CannonsPlugin p) {
        plugin = p;
        KEY_CHUNK = new NamespacedKey(p, "foundation");
        KEY_BLOCKS = new NamespacedKey(p, "foundation_blocks");
    }

    public static boolean complete(Block spot) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!spot.getRelative(dx, -1, dz).isSolid()) return false;
            }
        }
        return true;
    }

    public static void lay(Interaction anchor) {
        Block under = anchor.getLocation().getBlock().getRelative(0, -1, 0);
        List<String> mine = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block b = under.getRelative(dx, 0, dz);
                if (!b.isSolid()) continue;
                if (ownerOf(b) != null) continue;
                put(b, anchor.getUniqueId());
                mine.add(pos(b));
            }
        }
        anchor.getPersistentDataContainer().set(KEY_BLOCKS, PersistentDataType.LIST.strings(), mine);
    }

    public static void lift(Interaction anchor) {
        List<String> mine = anchor.getPersistentDataContainer().get(KEY_BLOCKS, PersistentDataType.LIST.strings());
        if (mine == null) return;
        World world = anchor.getWorld();
        for (String p : mine) {
            String[] xyz = p.split(",");
            Block b = world.getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            if (anchor.getUniqueId().equals(ownerOf(b))) remove(b);
        }
        anchor.getPersistentDataContainer().remove(KEY_BLOCKS);
    }

    public static UUID ownerOf(Block b) {
        return chunk(b.getChunk()).get(pos(b));
    }

    private static String pos(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private static String id(Chunk c) {
        return c.getWorld().getUID() + ":" + c.getX() + ":" + c.getZ();
    }

    private static Map<String, UUID> chunk(Chunk c) {
        return cache.computeIfAbsent(id(c), k -> {
            Map<String, UUID> map = new HashMap<>();
            List<String> saved = c.getPersistentDataContainer().get(KEY_CHUNK, PersistentDataType.LIST.strings());
            if (saved != null) {
                for (String s : saved) {
                    int cut = s.lastIndexOf(',');
                    try {
                        map.put(s.substring(0, cut), UUID.fromString(s.substring(cut + 1)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return map;
        });
    }

    private static void put(Block b, UUID anchor) {
        Map<String, UUID> map = chunk(b.getChunk());
        map.put(pos(b), anchor);
        save(b.getChunk(), map);
    }

    private static void remove(Block b) {
        Map<String, UUID> map = chunk(b.getChunk());
        if (map.remove(pos(b)) != null) save(b.getChunk(), map);
    }

    private static void save(Chunk c, Map<String, UUID> map) {
        if (map.isEmpty()) {
            c.getPersistentDataContainer().remove(KEY_CHUNK);
            return;
        }
        List<String> out = new ArrayList<>();
        map.forEach((p, id) -> out.add(p + "," + id));
        c.getPersistentDataContainer().set(KEY_CHUNK, PersistentDataType.LIST.strings(), out);
    }

    public static void explode(Location at, float power, double fireChance, boolean breakBlocks, Entity source) {
        pendingPower = power;
        pendingFire = fireChance;
        try {
            at.getWorld().createExplosion(at, power, false, breakBlocks, source);
        } finally {
            pendingPower = Float.NaN;
            pendingFire = 0;
        }
    }

    static double fireChance() {
        return Float.isNaN(pendingPower) ? 0 : pendingFire;
    }

    static void ignite(List<Block> blocks, double chance) {
        if (chance <= 0 || blocks.isEmpty()) return;
        List<Block> spots = new ArrayList<>(blocks);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (Block b : spots) {
                if (rng.nextDouble() >= chance || !b.getType().isAir()) continue;
                if (!b.getRelative(0, -1, 0).isSolid()) continue;
                b.setType(org.bukkit.Material.FIRE);
            }
        });
    }

    static void primed(Entity e, float power) {
        primed.put(e.getUniqueId(), power);
    }

    static float powerOf(Entity source, float fallback) {
        if (!Float.isNaN(pendingPower)) return pendingPower;
        if (source != null) {
            Float p = primed.remove(source.getUniqueId());
            if (p != null) return p;
        }
        return fallback;
    }

    // same ray falloff as vanilla
    public static boolean survives(Block b, Location center, float power) {
        double d = b.getLocation().add(0.5, 0.5, 0.5).distance(center);
        double intensity = power * (0.7 + 0.6 * ThreadLocalRandom.current().nextDouble()) - 0.75 * d;
        return intensity - (BLAST_RESISTANCE + 0.3) * 0.3 <= 0;
    }

    public static Set<UUID> sift(List<Block> blocks, Location center, float power) {
        Set<UUID> broken = new HashSet<>();
        blocks.removeIf(b -> {
            UUID owner = ownerOf(b);
            if (owner == null) return false;
            if (survives(b, center, power)) return true;
            broken.add(owner);
            return false;
        });
        return broken;
    }
}
