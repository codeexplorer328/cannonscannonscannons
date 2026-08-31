package org.castleway.cannons;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// a cannon is a bunch of entities working together
public final class Cannon {
    public static NamespacedKey KEY_TYPE, KEY_ROLE, KEY_OWNER, KEY_YAW, KEY_AMMO, KEY_ELEVATION, KEY_BONE, KEY_LANYARD;

    public static final double MUZZLE_TARGET_SIZE = 0.9;

    static void initKeys(CannonsPlugin plugin) {
        KEY_TYPE   = new NamespacedKey(plugin, "cannon_type");
        KEY_ROLE   = new NamespacedKey(plugin, "cannon_role");
        KEY_OWNER  = new NamespacedKey(plugin, "cannon_owner");
        KEY_AMMO = new NamespacedKey(plugin, "cannon_ammo");
        KEY_ELEVATION = new NamespacedKey(plugin, "cannon_elevation");
        KEY_BONE = new NamespacedKey(plugin, "cannon_bone");
        KEY_LANYARD = new NamespacedKey(plugin, "firing_lanyard");
        KEY_YAW    = new NamespacedKey(plugin, "cannon_yaw");
    }

    private final CannonType type;
    private final Interaction anchor;
    private final List<ItemDisplay> displays = new ArrayList<>();
    private Entity seat;
    private Interaction muzzleTarget;
    private float lastSeatYaw = Float.NaN;

    private static final float SEAT_FOLLOW_THRESHOLD = 4f;

    private Cannon(CannonType type, Interaction anchor) {
        this.type = type;
        this.anchor = anchor;
    }

    public CannonType type()       { return type; }
    public Interaction anchor()    { return anchor; }
    public UUID id()               { return anchor.getUniqueId(); }
    public Location origin()       { return anchor.getLocation(); }
    // which way the barrel points
    public float yaw()             { return anchor.getPersistentDataContainer()
                                        .getOrDefault(KEY_YAW, PersistentDataType.FLOAT, 0f); }

    // the model barrel faces negative z but yaw 0 faces positive z
    private static float forwardOffset() {
        return plugin != null ? plugin.modelForward() : 180f;
    }
    public float modelYaw()        { return yaw() + forwardOffset(); }


    private static CannonsPlugin plugin;

    static void attach(CannonsPlugin p) { plugin = p; }

    private static BoneDef.RotationTuning tuning() {
        return plugin != null ? plugin.rotationTuning() : BoneDef.RotationTuning.DEFAULT;
    }

    public void refresh() {
        aim(yaw(), currentElevation());
    }

    public static Cannon spawn(CannonType type, Location loc, float yaw, UUID owner) {
        World world = loc.getWorld();
        Location base = loc.clone();
        base.setYaw(yaw);
        base.setPitch(0f);

        Interaction anchor = world.spawn(base, Interaction.class, e -> {
            e.setInteractionWidth((float) type.width);
            e.setInteractionHeight((float) type.height);
            e.setResponsive(true);
            e.setPersistent(true);
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(KEY_TYPE, PersistentDataType.STRING, type.id);
            pdc.set(KEY_ROLE, PersistentDataType.STRING, "anchor");
            pdc.set(KEY_YAW, PersistentDataType.FLOAT, yaw);
            if (owner != null) pdc.set(KEY_OWNER, PersistentDataType.STRING, owner.toString());
        });

        Cannon cannon = new Cannon(type, anchor);
        cannon.buildDisplays();
        cannon.buildSeat();
        cannon.buildMuzzleTarget();
        cannon.aim(yaw, cannon.currentElevation());
        return cannon;
    }

    private void buildDisplays() {
        World world = anchor.getWorld();
        float myaw = modelYaw();
        int index = 0;
        for (BoneDef bone : type.bones) {
            final int boneIndex = index++;
            Vector off = rotateY(bone.translation(), myaw);
            Location at = anchor.getLocation().add(off);
            at.setYaw(myaw);
            at.setPitch(0f);

            ItemDisplay d = world.spawn(at, ItemDisplay.class, e -> {
                ItemStack stack;
                if (plugin != null && !plugin.customModels()) {
                    // debug mode uses vanilla blocks so no pack is needed
                    stack = new ItemStack(plugin.debugBlock(boneIndex));
                } else {
                    stack = new ItemStack(Material.PAPER);
                    stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(bone.itemModel()));
                }
                e.setItemStack(stack);
                // HEAD would pull in the parent display transform so use NONE
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setTransformation(new Transformation(
                        new Vector3f(0f, 0f, 0f),
                        bone.toQuaternion(tuning()),
                        new Vector3f(bone.scale(), bone.scale(), bone.scale()),
                        new org.joml.Quaternionf()));
                e.setPersistent(true);
                e.setBrightness(null);
                e.setViewRange(2.5f);
                e.setTeleportDuration(2);   // stops it snapping when turning
                e.getPersistentDataContainer()
                 .set(KEY_ROLE, PersistentDataType.STRING, "display");
                e.getPersistentDataContainer()
                 .set(KEY_BONE, PersistentDataType.STRING, bone.name());
                e.getPersistentDataContainer()
                 .set(KEY_OWNER, PersistentDataType.STRING, anchor.getUniqueId().toString());
            });
            displays.add(d);
        }
    }

    // a horse rider sits about this high above the horse
    private static final double HORSE_RIDER_HEIGHT = 1.1;

    private void buildSeat() {
        Location at = anchor.getLocation().add(rotateY(seatLocal(currentElevation()), modelYaw()));
        at.setYaw(yaw());

        if (plugin != null && plugin.horseSeat()) {
            seat = buildHorse(at.clone().subtract(0, HORSE_RIDER_HEIGHT, 0));
        } else {
            seat = buildStand(at);
        }
        tag(seat);
        lastSeatYaw = yaw();
    }

    // the horse only exists so the client shows the jump bar
    private Horse buildHorse(Location at) {
        Horse horse = anchor.getWorld().spawn(at, Horse.class, e -> {
            e.setInvisible(true);
            e.setSilent(true);
            e.setInvulnerable(true);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setCollidable(false);
            e.setGravity(false);
            e.setTamed(true);
            e.setAdult();
            e.setAgeLock(true);
            e.setEatingHaystack(false);
            var speed = e.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0.0);
            var jump = e.getAttribute(Attribute.JUMP_STRENGTH);
            if (jump != null) jump.setBaseValue(0.5);
            // 2 hp shows as one heart and thats the lowest it goes
            var hp = e.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(2.0);
            e.setHealth(2.0);
            e.getInventory().setSaddle(invisibleSaddle());
        });
        Bukkit.getMobGoals().removeAllGoals(horse);
        return horse;
    }

    private static ItemStack invisibleSaddle() {
        ItemStack saddle = new ItemStack(Material.SADDLE);
        saddle.setData(DataComponentTypes.EQUIPPABLE,
                Equippable.equippable(EquipmentSlot.SADDLE)
                        .assetId(Key.key("cannons", "nothing"))
                        .build());
        return saddle;
    }

    private ArmorStand buildStand(Location at) {
        return anchor.getWorld().spawn(at, ArmorStand.class, e -> {
            e.setMarker(true);
            e.setInvisible(true);
            e.setGravity(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setPersistent(true);
            e.setBasePlate(false);
            e.setCollidable(false);
        });
    }

    public Entity seat() { return seat; }

    // the heavy cannon seat rides the barrel but the makeshift seat stays on the ground
    private Vector seatLocal(float elevation) {
        Vector off = type.seatOffset.clone();
        BoneDef barrel = type.bone(type.barrelBone);
        if (type.seatOnBarrel && barrel != null) {
            Vector arm = off.subtract(barrel.translation());
            double r = Math.toRadians(elevation * tuning().elevationSign());
            double y = arm.getY(), z = arm.getZ();
            arm.setY(y * Math.cos(r) - z * Math.sin(r));
            arm.setZ(y * Math.sin(r) + z * Math.cos(r));
            off = barrel.translation().clone().add(arm);
        }
        return off;
    }

    public Location seatHome() {
        Location at = anchor.getLocation().add(rotateY(seatLocal(currentElevation()), modelYaw()));
        if (seat instanceof Horse) at.subtract(0, HORSE_RIDER_HEIGHT, 0);
        return at;
    }

    // sits inside the body box but wins when looking down the barrel
    private void buildMuzzleTarget() {
        Location at = muzzle(currentElevation()).subtract(0, MUZZLE_TARGET_SIZE / 2.0, 0);
        muzzleTarget = anchor.getWorld().spawn(at, Interaction.class, e -> {
            e.setInteractionWidth((float) MUZZLE_TARGET_SIZE);
            e.setInteractionHeight((float) MUZZLE_TARGET_SIZE);
            e.setResponsive(true);
            e.setPersistent(true);
            e.getPersistentDataContainer().set(KEY_ROLE, PersistentDataType.STRING, "muzzle");
            e.getPersistentDataContainer()
             .set(KEY_OWNER, PersistentDataType.STRING, anchor.getUniqueId().toString());
        });
    }

    private void tag(Entity e) {
        e.getPersistentDataContainer().set(KEY_ROLE, PersistentDataType.STRING, "seat");
        e.getPersistentDataContainer()
         .set(KEY_OWNER, PersistentDataType.STRING, anchor.getUniqueId().toString());
    }


    public void mount(Player player) {
        if (seat != null && seat.isValid() && seat.getPassengers().isEmpty()) {
            seat.addPassenger(player);
        }
    }

    public Location muzzle() {
        return anchor.getLocation().add(rotateY(type.muzzleOffset, modelYaw()));
    }

    public Vector aimDirection(float elevationDegrees) {
        double yawRad = Math.toRadians(yaw());
        double pitchRad = Math.toRadians(elevationDegrees);
        double horizontal = Math.cos(pitchRad);
        return new Vector(
                -Math.sin(yawRad) * horizontal,
                Math.sin(pitchRad),
                Math.cos(yawRad) * horizontal).normalize();
    }

    public void aim(float yawDegrees, float elevation) {
        anchor.getPersistentDataContainer()
              .set(KEY_YAW, PersistentDataType.FLOAT, yawDegrees);

        BoneDef barrel = type.bone(type.barrelBone);
        float elevSigned = elevation * tuning().elevationSign();

        for (int i = 0; i < type.bones.size(); i++) {
            BoneDef bone = type.bones.get(i);
            if (i >= displays.size()) break;
            ItemDisplay d = displays.get(i);
            if (!d.isValid()) continue;

            boolean swings = type.underBarrel(bone);

            // anything attached to the barrel swings with it
            Vector pivot = bone.translation().clone();
            if (swings && barrel != null && !bone.name().equals(barrel.name())) {
                Vector arm = pivot.clone().subtract(barrel.translation());
                double r = Math.toRadians(elevSigned);
                double y = arm.getY(), z = arm.getZ();
                arm.setY(y * Math.cos(r) - z * Math.sin(r));
                arm.setZ(y * Math.sin(r) + z * Math.cos(r));
                pivot = barrel.translation().clone().add(arm);
            }

            float myaw = yawDegrees + forwardOffset();
            Location at = anchor.getLocation().add(rotateY(pivot, myaw));
            at.setYaw(myaw);
            at.setPitch(0f);

            float extraX = swings ? elevSigned : 0f;
            Transformation t = d.getTransformation();
            t.getLeftRotation().set(new BoneDef(
                    bone.name(), bone.itemModel(), bone.translation(),
                    bone.rotX() + extraX, bone.rotY(), bone.rotZ(),
                    bone.scale(), bone.parent()).toQuaternion(tuning()));
            d.setInterpolationDuration(2);
            d.setInterpolationDelay(0);
            d.setTransformation(t);
            d.teleport(at);
        }

        moveSeat(yawDegrees);

        anchor.getPersistentDataContainer()
              .set(KEY_ELEVATION, PersistentDataType.FLOAT, elevation);
        if (muzzleTarget != null && muzzleTarget.isValid()) {
            muzzleTarget.teleport(muzzle(elevation).subtract(0, MUZZLE_TARGET_SIZE / 2.0, 0));
        }
    }

    // TODO tune this since teleporting a ridden entity every tick rubber bands
    private void moveSeat(float yawDegrees) {
        if (seat == null || !seat.isValid()) return;
        if (Math.abs(angleDelta(yawDegrees, lastSeatYaw)) < SEAT_FOLLOW_THRESHOLD) return;
        lastSeatYaw = yawDegrees;
        Location at = seatHome();
        at.setYaw(seat.getLocation().getYaw());
        at.setPitch(0f);
        seat.teleport(at);
    }

    public static float angleDelta(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    public float currentElevation() {
        return anchor.getPersistentDataContainer()
                .getOrDefault(KEY_ELEVATION, PersistentDataType.FLOAT, 0f);
    }

    public Location muzzle(float elevation) {
        Vector local = type.muzzleOffset.clone();
        double r = Math.toRadians(elevation);
        double y = local.getY(), z = local.getZ();
        local.setY(y * Math.cos(r) - z * Math.sin(r));
        local.setZ(y * Math.sin(r) + z * Math.cos(r));
        return anchor.getLocation().add(rotateY(local, modelYaw()));
    }

    public void remove() {
        for (ItemDisplay d : displays) if (d.isValid()) d.remove();
        if (muzzleTarget != null && muzzleTarget.isValid()) muzzleTarget.remove();
        if (seat != null && seat.isValid()) {
            seat.eject();
            seat.remove();
        }
        if (anchor.isValid()) anchor.remove();
    }


    public static Cannon adoptFromPart(Entity part) {
        String ownerId = part.getPersistentDataContainer()
                .get(KEY_OWNER, PersistentDataType.STRING);
        if (ownerId == null) return null;
        UUID uuid;
        try { uuid = UUID.fromString(ownerId); } catch (IllegalArgumentException e) { return null; }
        Entity owner = part.getWorld().getEntity(uuid);
        return (owner instanceof Interaction anchor) ? adopt(anchor) : null;
    }

    public static Cannon adopt(Interaction anchor) {
        String typeId = anchor.getPersistentDataContainer()
                .get(KEY_TYPE, PersistentDataType.STRING);
        CannonType type = CannonType.byId(typeId);
        if (type == null) return null;

        Cannon cannon = new Cannon(type, anchor);
        String selfId = anchor.getUniqueId().toString();

        // match by bone name because the entity order is random
        Map<String, ItemDisplay> byBone = new HashMap<>();
        for (Entity e : anchor.getWorld().getNearbyEntities(
                anchor.getLocation(), 8, 8, 8)) {
            String owner = e.getPersistentDataContainer()
                    .get(KEY_OWNER, PersistentDataType.STRING);
            if (!selfId.equals(owner)) continue;
            String role = e.getPersistentDataContainer()
                    .get(KEY_ROLE, PersistentDataType.STRING);
            if ("display".equals(role) && e instanceof ItemDisplay d) {
                String bone = d.getPersistentDataContainer()
                        .get(KEY_BONE, PersistentDataType.STRING);
                if (bone != null) byBone.put(bone, d);
            } else if ("seat".equals(role)) {
                cannon.seat = e;
                cannon.lastSeatYaw = cannon.yaw();
            } else if ("muzzle".equals(role) && e instanceof Interaction i) {
                cannon.muzzleTarget = i;
            }
        }
        for (BoneDef bone : type.bones) {
            ItemDisplay d = byBone.get(bone.name());
            if (d != null) cannon.displays.add(d);
        }
        return cannon;
    }

    static Vector rotateY(Vector v, float yawDegrees) {
        double r = Math.toRadians(-yawDegrees);
        double cos = Math.cos(r), sin = Math.sin(r);
        return new Vector(v.getX() * cos + v.getZ() * sin, v.getY(),
                         -v.getX() * sin + v.getZ() * cos);
    }
}
