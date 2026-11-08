package org.castleway.cannons;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public final class SmokeCloud extends BukkitRunnable {

    private final World world;
    private final Location center;
    private final double radius;
    private final int lifetime;
    private int ticks = 0;

    // each smoke powder adds a block of radius and two seconds
    public static void start(CannonsPlugin plugin, Location at, int smoke) {
        if (smoke <= 0) return;
        new SmokeCloud(at, 2.0 + smoke, 20 * (3 + 2 * smoke)).runTaskTimer(plugin, 0L, 2L);
    }

    private SmokeCloud(Location center, double radius, int lifetime) {
        this.world = center.getWorld();
        this.center = center.clone().add(0, radius * 0.4, 0);
        this.radius = radius;
        this.lifetime = lifetime;
    }

    @Override
    public void run() {
        ticks += 2;
        if (ticks > lifetime) { cancel(); return; }
        double fade = ticks > lifetime - 40 ? (lifetime - ticks) / 40.0 : 1.0;
        int count = (int) Math.max(4, radius * radius * 1.5 * fade);
        double spread = radius * 0.5;
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center, count, spread, spread * 0.6, spread,
                0.004, null, true);
        world.spawnParticle(Particle.LARGE_SMOKE, center, count / 2, spread, spread * 0.6, spread,
                0.01, null, true);
        if (ticks % 10 != 0) return;
        for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living.getLocation().distanceSquared(center) > radius * radius) continue;
            living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, false));
            // nausea needs longer to kick in
            living.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, false, false, false));
        }
    }
}
