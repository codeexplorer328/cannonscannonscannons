package org.castleway.cannons;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.UUID;

// ballista arrow with gravity and drag
public final class BallistaBolt extends BukkitRunnable {
    public static final double GRAVITY = 0.05;
    public static final double DRAG = 0.99;
    public static final double DAMAGE = 18.0;
    public static final double KNOCKBACK = 1.2;
    public static final double WEAPON_DAMAGE = 10.0;

    private static final int MAX_FLIGHT_TICKS = 20 * 15;
    private static final int STUCK_TICKS = 20 * 20;
    private static final double HIT_RADIUS = 0.35;

    private final World world;
    private final ItemDisplay a, b;
    private final UUID shooterId;
    private final UUID ownAnchor;
    private final float scale;

    private Location position;
    private final Vector velocity;
    private int ticks = 0;
    private int stuckFor = -1;

    public BallistaBolt(CannonsPlugin plugin, Location start, Vector direction,
                        double speed, float scale, Player shooter, UUID ownAnchor) {
        this.world = start.getWorld();
        this.position = start.clone();
        this.velocity = direction.clone().normalize().multiply(speed);
        this.scale = scale;
        this.shooterId = shooter != null ? shooter.getUniqueId() : null;
        this.ownAnchor = ownAnchor;
        this.a = spawnSprite(false);
        this.b = spawnSprite(true);
        world.spawnParticle(Particle.CRIT, start, 12, 0.15, 0.15, 0.15, 0.2);
    }

    private ItemDisplay spawnSprite(boolean crossed) {
        Location at = position.clone();
        at.setYaw(0f);
        at.setPitch(0f);
        return world.spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.ARROW));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setPersistent(false);
            e.setViewRange(4f);
            e.setTeleportDuration(1);
            e.setTransformationMatrix(orient(velocity, crossed, scale));
        });
    }

    static Matrix4f orient(Vector v, boolean crossed, float scale) {
        double h = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        float yaw = (float) Math.atan2(-v.getX(), -v.getZ());
        float pitch = (float) Math.atan2(v.getY(), h);
        return new Matrix4f().rotateY(yaw).rotateX(pitch).mul(Ballista.boltMatrix(crossed, scale));
    }

    @Override
    public void run() {
        ticks++;
        if (stuckFor >= 0) {
            if (++stuckFor > STUCK_TICKS) cleanup();
            return;
        }
        if (ticks > MAX_FLIGHT_TICKS) { cleanup(); return; }

        double step = velocity.length();
        Vector dir = velocity.clone().normalize();

        Entity hitEntity = null;
        double hitDistance = step;
        RayTraceResult hit = world.rayTraceEntities(position, dir, step, HIT_RADIUS,
                e -> canHit(e, shooterId, ownAnchor, ticks));
        if (hit != null && hit.getHitEntity() != null) {
            hitEntity = hit.getHitEntity();
            hitDistance = hit.getHitPosition().distance(position.toVector());
        }
        RayTraceResult block = world.rayTraceBlocks(position, dir, step, FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitBlock() != null) {
            double d = block.getHitPosition().distance(position.toVector());
            if (d < hitDistance) {
                stick(block.getHitPosition().toLocation(world).subtract(dir.clone().multiply(0.3)));
                return;
            }
        }
        if (hitEntity != null) {
            strike(hitEntity, dir);
            return;
        }

        position.add(velocity);
        if (position.getY() < world.getMinHeight() - 8) { cleanup(); return; }
        move(true);
        velocity.setY(velocity.getY() - GRAVITY);
        velocity.multiply(DRAG);
        if (ticks % 2 == 0) world.spawnParticle(Particle.CRIT, position, 1, 0, 0, 0, 0);
    }

    static Interaction weaponHit(Entity e, UUID ownAnchor) {
        Interaction w = WeaponHealth.weaponOf(e);
        if (w == null || w.getUniqueId().equals(ownAnchor)) return null;
        return w;
    }

    static boolean canHit(Entity e, UUID shooterId, UUID ownAnchor, int ticks) {
        if (e instanceof Interaction || e.getPersistentDataContainer().has(Cannon.KEY_ROLE, PersistentDataType.STRING)) {
            return weaponHit(e, ownAnchor) != null;
        }
        if (!(e instanceof LivingEntity)) return false;
        if (shooterId != null && ticks < 6 && e.getUniqueId().equals(shooterId)) return false;
        if (e instanceof ArmorStand stand && stand.isMarker()) return false;
        return !e.isDead();
    }

    private void strike(Entity target, Vector dir) {
        Player shooter = shooterId != null ? Bukkit.getPlayer(shooterId) : null;
        Interaction weapon = weaponHit(target, ownAnchor);
        if (weapon != null) {
            WeaponHealth.damage(weapon, WEAPON_DAMAGE, shooter);
            cleanup();
            return;
        }
        if (target instanceof LivingEntity living) {
            if (shooter != null) living.damage(DAMAGE, shooter);
            else living.damage(DAMAGE);
            living.setVelocity(living.getVelocity().add(dir.clone().multiply(KNOCKBACK).setY(0.35)));
        }
        world.spawnParticle(Particle.CRIT, target.getLocation().add(0, target.getHeight() / 2, 0),
                16, 0.2, 0.2, 0.2, 0.3);
        cleanup();
    }

    private void stick(Location at) {
        position = at;
        move(false);
        stuckFor = 0;
        world.playSound(at, Sound.ENTITY_ARROW_HIT, 1.4f, 0.5f);
    }

    private void move(boolean turn) {
        if (a.isValid()) a.teleport(position);
        if (b.isValid()) b.teleport(position);
        if (!turn) return;
        a.setInterpolationDelay(0);
        a.setInterpolationDuration(1);
        a.setTransformationMatrix(orient(velocity, false, scale));
        b.setInterpolationDelay(0);
        b.setInterpolationDuration(1);
        b.setTransformationMatrix(orient(velocity, true, scale));
    }

    private void cleanup() {
        cancel();
        if (a.isValid()) a.remove();
        if (b.isValid()) b.remove();
    }
}
