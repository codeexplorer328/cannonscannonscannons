package org.castleway.cannons;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// ballista rocket that flies straight and explodes
public final class BallistaRocket extends BukkitRunnable {
    public static NamespacedKey KEY_SHOW;

    public static final double DAMAGE_MULTIPLIER = 4.0;
    private static final double RADIUS = 5.0;
    private static final float EXPLOSION_POWER = 2f;
    private static final double HIT_RADIUS = 0.35;

    static void init(CannonsPlugin p) {
        KEY_SHOW = new NamespacedKey(p, "rocket_show");
    }

    private final World world;
    private final ItemDisplay sprite;
    private final ItemStack rocket;
    private final UUID shooterId;
    private final UUID ownAnchor;
    private final Vector velocity;
    private final float scale;
    private final int lifetime;
    private final Location position;
    private int ticks = 0;

    public BallistaRocket(Location start, Vector direction, double speed, float scale,
                          ItemStack rocket, Player shooter, UUID ownAnchor) {
        this.world = start.getWorld();
        this.position = start.clone();
        this.velocity = direction.clone().normalize().multiply(speed);
        this.scale = scale;
        this.rocket = rocket.clone();
        this.shooterId = shooter != null ? shooter.getUniqueId() : null;
        this.ownAnchor = ownAnchor;
        this.lifetime = fuse(this.rocket);
        this.sprite = spawnSprite();
        world.playSound(start, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2f, 0.8f);
    }

    static int fuse(ItemStack rocket) {
        int flight = rocket.getItemMeta() instanceof FireworkMeta m ? m.getPower() : 1;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return 10 * (1 + flight) + rng.nextInt(6) + rng.nextInt(7);
    }

    static double damage(ItemStack rocket) {
        int stars = rocket.getItemMeta() instanceof FireworkMeta m ? m.getEffectsSize() : 0;
        return DAMAGE_MULTIPLIER * (5 + 2 * stars);
    }

    private ItemDisplay spawnSprite() {
        Location at = position.clone();
        at.setYaw(0f);
        at.setPitch(0f);
        return world.spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.FIREWORK_ROCKET));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setPersistent(false);
            e.setViewRange(4f);
            e.setTeleportDuration(1);
            e.setTransformationMatrix(along(velocity, scale));
        });
    }

    private static Matrix4f along(Vector v, float scale) {
        double h = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        float yaw = (float) Math.atan2(-v.getX(), -v.getZ());
        float pitch = (float) Math.atan2(v.getY(), h);
        return new Matrix4f().rotateY(yaw).rotateX(pitch).mul(Ballista.rocketSprite(scale));
    }

    @Override
    public void run() {
        ticks++;
        if (ticks > lifetime) { burst(position, null); return; }

        double step = velocity.length();
        Vector dir = velocity.clone().normalize();
        Entity hitEntity = null;
        double hitDistance = step;
        RayTraceResult hit = world.rayTraceEntities(position, dir, step, HIT_RADIUS,
                e -> BallistaBolt.canHit(e, shooterId, ownAnchor, ticks));
        if (hit != null && hit.getHitEntity() != null) {
            hitEntity = hit.getHitEntity();
            hitDistance = hit.getHitPosition().distance(position.toVector());
        }
        RayTraceResult block = world.rayTraceBlocks(position, dir, step, FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitBlock() != null
                && block.getHitPosition().distance(position.toVector()) < hitDistance) {
            burst(block.getHitPosition().toLocation(world).subtract(dir.clone().multiply(0.2)), null);
            return;
        }
        if (hitEntity != null) {
            burst(hit.getHitPosition().toLocation(world), hitEntity);
            return;
        }

        Location from = position.clone();
        position.add(velocity);
        if (position.getY() < world.getMinHeight() - 8) { burst(from, null); return; }
        if (sprite.isValid()) sprite.teleport(position);
        trail(from, position);
    }

    // flame every half block
    private void trail(Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector());
        int points = Math.max(1, (int) Math.ceil(step.length() * 2));
        for (int i = 0; i < points; i++) {
            Location at = from.clone().add(step.clone().multiply(i / (double) points));
            world.spawnParticle(Particle.FLAME, at, 1, 0.03, 0.03, 0.03, 0.005, null, true);
            if (i % 2 == 0) world.spawnParticle(Particle.SMOKE, at, 1, 0.05, 0.05, 0.05, 0.01, null, true);
        }
    }

    private void burst(Location at, Entity direct) {
        cleanup();
        Player shooter = shooterId != null ? Bukkit.getPlayer(shooterId) : null;

        // firework is only for show
        if (rocket.getItemMeta() instanceof FireworkMeta meta && meta.hasEffects()) {
            Firework show = world.spawn(at, Firework.class, f -> {
                f.setFireworkMeta(meta);
                f.getPersistentDataContainer().set(KEY_SHOW, PersistentDataType.BYTE, (byte) 1);
            });
            show.detonate();
        }
        // explosions only show within 64 blocks
        world.spawnParticle(Particle.EXPLOSION, at, 3, 0.4, 0.4, 0.4, 0, null, true);
        world.spawnParticle(Particle.FLAME, at, 40, 0.3, 0.3, 0.3, 0.15, null, true);
        world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 8f, 0.9f);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 6f, 1.2f);

        double base = damage(rocket);
        for (Entity e : world.getNearbyEntities(at, RADIUS, RADIUS, RADIUS)) {
            if (!(e instanceof LivingEntity living) || living.isDead()) continue;
            if (e.getPersistentDataContainer().has(Cannon.KEY_ROLE, PersistentDataType.STRING)) continue;
            double d = e == direct ? 0 : living.getLocation().add(0, living.getHeight() / 2, 0).distance(at);
            if (d >= RADIUS || (e != direct && !inSight(at, living))) continue;
            double dmg = base * Math.sqrt((RADIUS - d) / RADIUS);
            if (shooter != null) living.damage(dmg, shooter);
            else living.damage(dmg);
        }
        Foundation.explode(at, EXPLOSION_POWER, Foundation.BASE_FIRE, true, shooter);
        WeaponHealth.blast(at, EXPLOSION_POWER, shooter);
    }

    private boolean inSight(Location from, LivingEntity target) {
        for (Location to : new Location[]{target.getLocation(), target.getEyeLocation()}) {
            Vector v = to.toVector().subtract(from.toVector());
            double len = v.length();
            if (len < 1e-3) return true;
            if (world.rayTraceBlocks(from, v.normalize(), len, FluidCollisionMode.NEVER, true) == null) return true;
        }
        return false;
    }

    private void cleanup() {
        cancel();
        if (sprite.isValid()) sprite.remove();
    }
}
