package org.castleway.cannons;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// cannonball with its own physics since falling blocks place themselves
public final class CannonBall extends BukkitRunnable {
    public static final double GRAVITY = 0.04;
    public static final double DRAG = 0.98;
    public static final double MAX_VELOCITY = 10.91765; // 383 blocks at 45 degrees
    public static final double MIN_VELOCITY = 0.62702; // 8 blocks at 45 degrees
    public static final float EXPLOSION_POWER = 4.0f;
    public static final float TNT_EXPLOSION_POWER = (float) (4.0 * Math.cbrt(10.0));
    public static final boolean EXPLOSION_BREAKS_BLOCKS = true;

    private static final int MAX_LIFETIME_TICKS = 20 * 20;
    private static final int MAX_WAIT_TICKS = 100;
    private static final double HIT_RADIUS = 0.5;

    private final CannonsPlugin plugin;
    private final World world;
    private final BlockDisplay visual;
    private final UUID shooterId;
    private final UUID cannonId;

    private final String cannonOwnerTag;
    private final Material block;
    private final double fireChance;
    private final int smoke;
    private final Set<Long> tickets = new HashSet<>();
    private boolean waiting = false;
    private int waited = 0;

    private Location position;
    private Vector velocity;
    private int ticks = 0;

    private final Vector3f spinAxisA;
    private final Vector3f spinAxisB;
    private final Quaternionf spin = new Quaternionf();

    public CannonBall(CannonsPlugin plugin, Location start, Vector direction,
                      double speed, Material block, Player shooter, UUID cannonId, double fireChance, int smoke) {
        this.plugin = plugin;
        this.world = start.getWorld();
        this.position = start.clone();
        this.velocity = direction.clone().normalize().multiply(speed);
        this.shooterId = shooter != null ? shooter.getUniqueId() : null;
        this.cannonId = cannonId;
        this.cannonOwnerTag = cannonId != null ? cannonId.toString() : null;
        this.block = block;
        this.fireChance = fireChance;
        this.smoke = smoke;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        this.spinAxisA = randomAxis(rng);
        this.spinAxisB = randomAxis(rng);

        this.visual = world.spawn(start, BlockDisplay.class, e -> {
            e.setBlock(block.createBlockData());
            e.setPersistent(false);
            e.setViewRange(3.0f);
            e.setTeleportDuration(1);
            org.bukkit.util.Transformation t = e.getTransformation();
            t.getTranslation().set(-0.5f, -0.5f, -0.5f);
            e.setTransformation(t);
        });

        muzzleBlast(start, direction.clone().normalize());
    }

    private void muzzleBlast(Location muzzle, Vector dir) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f);
        world.playSound(muzzle, Sound.ITEM_FIRECHARGE_USE, 1.6f, 0.7f);
        world.spawnParticle(Particle.EXPLOSION, muzzle, 1);

        for (int i = 0; i < 60; i++) {
            Vector jitter = dir.clone()
                    .add(new Vector(rng.nextGaussian() * 0.22,
                                    rng.nextGaussian() * 0.22,
                                    rng.nextGaussian() * 0.22))
                    .normalize();
            double speed = 0.35 + rng.nextDouble() * 0.65;
            world.spawnParticle(Particle.LARGE_SMOKE, muzzle, 0,
                    jitter.getX(), jitter.getY(), jitter.getZ(), speed);
        }
        for (int i = 0; i < 18; i++) {
            Vector jitter = dir.clone()
                    .add(new Vector(rng.nextGaussian() * 0.12,
                                    rng.nextGaussian() * 0.12,
                                    rng.nextGaussian() * 0.12))
                    .normalize();
            world.spawnParticle(Particle.FLAME, muzzle, 0,
                    jitter.getX(), jitter.getY(), jitter.getZ(),
                    0.5 + rng.nextDouble() * 0.5);
        }

        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, muzzle, 8,
                0.25, 0.25, 0.25, 0.02);
    }

    private static Vector3f randomAxis(ThreadLocalRandom rng) {
        Vector3f axis = new Vector3f(
                (float) rng.nextGaussian(),
                (float) rng.nextGaussian(),
                (float) rng.nextGaussian());
        if (axis.lengthSquared() < 1e-6f) axis.set(0f, 1f, 0f);
        return axis.normalize();
    }

    private static final float MAX_SPIN = 0.3f;

    private void tumble(double speed) {
        float rateA = (float) Math.min(speed * 0.22, MAX_SPIN);
        float rateB = (float) Math.min(speed * 0.13, MAX_SPIN * 0.6);
        spin.rotateAxis(rateA, spinAxisA).rotateAxis(rateB, spinAxisB);
        visual.setInterpolationDelay(0);
        visual.setInterpolationDuration(1);
        visual.setTransformationMatrix(new Matrix4f().rotate(spin).translate(-0.5f, -0.5f, -0.5f));
    }

    private boolean chunksReady(Location from, Location to) {
        boolean ready = true;
        for (Location l : new Location[]{from, to}) {
            int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
            long key = Chunk.getChunkKey(cx, cz);
            if (world.isChunkLoaded(cx, cz)) {
                if (tickets.add(key)) world.addPluginChunkTicket(cx, cz, plugin);
                continue;
            }
            ready = false;
            if (!waiting) {
                waiting = true;
                world.getChunkAtAsync(cx, cz).thenAccept(c -> waiting = false);
            }
        }
        return ready;
    }

    @Override
    public void run() {
        if (!chunksReady(position, position.clone().add(velocity))) {
            if (++waited > MAX_WAIT_TICKS) cleanup();
            return;
        }
        if (ticks++ > MAX_LIFETIME_TICKS) { detonate(position); return; }

        double stepLength = velocity.length();
        if (stepLength <= 1e-6) { detonate(position); return; }

        RayTraceResult blockHit = world.rayTraceBlocks(
                position, velocity.clone().normalize(), stepLength,
                org.bukkit.FluidCollisionMode.NEVER, true);

        Entity entityHit = findEntityAlongStep(stepLength);

        if (blockHit != null && entityHit == null) {
            detonate(blockHit.getHitPosition().toLocation(world));
            return;
        }
        if (entityHit != null) {
            detonate(entityHit.getLocation().add(0, entityHit.getHeight() / 2, 0));
            return;
        }

        position.add(velocity);
        if (position.getY() < world.getMinHeight() - 8
                || position.getY() > world.getMaxHeight() + 64) {
            cleanup();
            return;
        }

        visual.teleport(position);
        tumble(stepLength);
        velocity.setY(velocity.getY() - GRAVITY);
        velocity.multiply(DRAG);

        if (ticks % 2 == 0) {
            world.spawnParticle(Particle.SMOKE, position, 2, 0.05, 0.05, 0.05, 0.01);
        }
    }

    private Entity findEntityAlongStep(double stepLength) {
        Vector step = velocity.clone().normalize();

        int samples = Math.max(2, (int) Math.ceil(stepLength * 2));
        for (int i = 1; i <= samples; i++) {
            Location probe = position.clone().add(
                    step.clone().multiply(stepLength * i / samples));
            BoundingBox box = BoundingBox.of(probe, HIT_RADIUS, HIT_RADIUS, HIT_RADIUS);
            for (Entity e : world.getNearbyEntities(box)) {
                if (e.getUniqueId().equals(visual.getUniqueId())) continue;
                if (e.getUniqueId().equals(cannonId)) continue;
                if (cannonOwnerTag != null && cannonOwnerTag.equals(
                        e.getPersistentDataContainer()
                         .get(Cannon.KEY_OWNER, org.bukkit.persistence.PersistentDataType.STRING)))
                    continue;
                if (shooterId != null && e.getUniqueId().equals(shooterId) && ticks < 5) continue;
                if (BallistaBolt.weaponHit(e, cannonId) != null) return e;
                if (e.getPersistentDataContainer().has(Cannon.KEY_ROLE, org.bukkit.persistence.PersistentDataType.STRING)) continue;
                if (!(e instanceof LivingEntity) && !(e instanceof org.bukkit.entity.Vehicle)) continue;
                return e;
            }
        }
        return null;
    }

    private void detonate(Location at) {
        cleanup();
        Player shooter = shooterId != null ? plugin.getServer().getPlayer(shooterId) : null;
        float power = block == Material.TNT ? TNT_EXPLOSION_POWER : EXPLOSION_POWER;
        Foundation.explode(at, power, fireChance, EXPLOSION_BREAKS_BLOCKS, shooter);
        WeaponHealth.blast(at, power, shooter);
        SmokeCloud.start(plugin, at, smoke);
    }

    private void cleanup() {
        cancel();
        if (visual.isValid()) visual.remove();
        for (long key : tickets) world.removePluginChunkTicket((int) key, (int) (key >> 32), plugin);
        tickets.clear();
    }
}
