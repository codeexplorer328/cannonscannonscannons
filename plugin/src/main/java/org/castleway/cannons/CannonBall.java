package org.castleway.cannons;

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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// custom physics because FallingBlock places blocks when it lands
public final class CannonBall extends BukkitRunnable {
    public static final double GRAVITY = 0.04;
    public static final double DRAG = 0.98;
    public static final double MAX_VELOCITY = 1.99635;      // 50 blocks at 45 degrees
    public static final double MIN_VELOCITY = 0.62702;      // 8 blocks at 45 degrees
    public static final float EXPLOSION_POWER = 4.0f;       // same as tnt
    public static final boolean EXPLOSION_SETS_FIRE = false;
    public static final boolean EXPLOSION_BREAKS_BLOCKS = true;

    private static final int MAX_LIFETIME_TICKS = 20 * 15;
    private static final double HIT_RADIUS = 0.5;

    private final CannonsPlugin plugin;
    private final World world;
    private final BlockDisplay visual;
    private final UUID shooterId;
    private final UUID cannonId;

    private final String cannonOwnerTag;

    private Location position;
    private Vector velocity;
    private int ticks = 0;

    private final Vector3f spinAxisA;
    private final Vector3f spinAxisB;
    private final Quaternionf spin = new Quaternionf();

    public CannonBall(CannonsPlugin plugin, Location start, Vector direction,
                      double speed, Material block, Player shooter, UUID cannonId) {
        this.plugin = plugin;
        this.world = start.getWorld();
        this.position = start.clone();
        this.velocity = direction.clone().normalize().multiply(speed);
        this.shooterId = shooter != null ? shooter.getUniqueId() : null;
        this.cannonId = cannonId;
        this.cannonOwnerTag = cannonId != null ? cannonId.toString() : null;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        this.spinAxisA = randomAxis(rng);
        this.spinAxisB = randomAxis(rng);

        this.visual = world.spawn(start, BlockDisplay.class, e -> {
            e.setBlock(block.createBlockData());
            e.setPersistent(false);
            e.setViewRange(3.0f);
            // stops it stuttering
            e.setTeleportDuration(1);
            org.bukkit.util.Transformation t = e.getTransformation();
            t.getTranslation().set(-0.5f, -0.5f, -0.5f);
            e.setTransformation(t);
        });

        muzzleBlast(start, direction.clone().normalize());
    }

    // a count of 0 makes the offsets act as a direction
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

    private void tumble(double speed) {
        float rateA = (float) (speed * 0.22);
        float rateB = (float) (speed * 0.13);
        spin.rotateAxis(rateA, spinAxisA).rotateAxis(rateB, spinAxisB);

        org.bukkit.util.Transformation t = visual.getTransformation();
        t.getLeftRotation().set(spin);
        visual.setInterpolationDelay(0);
        visual.setInterpolationDuration(1);
        visual.setTransformation(t);
    }

    @Override
    public void run() {
        if (ticks++ > MAX_LIFETIME_TICKS) { detonate(position); return; }

        double stepLength = velocity.length();
        if (stepLength <= 1e-6) { detonate(position); return; }

        // raytrace the whole step so it cant tunnel through walls
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
                // dont hit our own seat horse
                if (cannonOwnerTag != null && cannonOwnerTag.equals(
                        e.getPersistentDataContainer()
                         .get(Cannon.KEY_OWNER, org.bukkit.persistence.PersistentDataType.STRING)))
                    continue;
                if (shooterId != null && e.getUniqueId().equals(shooterId) && ticks < 5) continue;
                if (!(e instanceof LivingEntity) && !(e instanceof org.bukkit.entity.Vehicle)) continue;
                return e;
            }
        }
        return null;
    }

    private void detonate(Location at) {
        cleanup();
        Player shooter = shooterId != null ? plugin.getServer().getPlayer(shooterId) : null;
        world.createExplosion(at, EXPLOSION_POWER,
                EXPLOSION_SETS_FIRE, EXPLOSION_BREAKS_BLOCKS, shooter);
    }

    private void cleanup() {
        cancel();
        if (visual.isValid()) visual.remove();
    }
}
