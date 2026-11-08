package org.castleway.cannons;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// one ballista built from displays on an anchor
public final class Ballista {
    public static final String ROLE = "ballista";
    public static NamespacedKey KEY_AIM, KEY_LOADED, KEY_AMMO_ITEM;

    private static final float TICK = 0.05f;
    private static final float SEAT_FOLLOW_DEGREES = 3f;

    enum Phase { IDLE, WINDING, COCKED, RELEASING }

    private static CannonsPlugin plugin;

    static void init(CannonsPlugin p) {
        plugin = p;
        KEY_AIM = new NamespacedKey(p, "ballista_aim");
        KEY_LOADED = new NamespacedKey(p, "ballista_loaded");
        KEY_AMMO_ITEM = new NamespacedKey(p, "ballista_ammo_item");
        BallistaType.loadRigs(p);
    }

    private final BallistaType type;
    private final BallistaRig rig;
    private final Interaction anchor;
    private final Map<String, ItemDisplay> pieces = new HashMap<>();
    private ItemDisplay arrowA, arrowB;
    private Entity seat;

    private float baseYaw, aimYaw, elevation;
    private boolean loaded;
    private ItemStack ammo;
    private Phase phase = Phase.IDLE;
    private float time = 0f;
    private boolean released;
    private UUID shooter;
    private float lastSeatYaw = Float.NaN;
    private boolean dirty = true;

    private Ballista(BallistaType type, Interaction anchor) {
        this.type = type;
        this.rig = type.rig();
        this.anchor = anchor;
        PersistentDataContainer pdc = anchor.getPersistentDataContainer();
        baseYaw = pdc.getOrDefault(Cannon.KEY_YAW, PersistentDataType.FLOAT, 0f);
        aimYaw = pdc.getOrDefault(KEY_AIM, PersistentDataType.FLOAT, baseYaw);
        elevation = pdc.getOrDefault(Cannon.KEY_ELEVATION, PersistentDataType.FLOAT, type.restElevation);
        loaded = pdc.getOrDefault(KEY_LOADED, PersistentDataType.BYTE, (byte) 0) == 1;
        if (loaded) ammo = readAmmo(pdc);
        if (loaded && plugin.ballistaWindOnLoad()) {
            phase = Phase.COCKED;
            time = type.windEnd;
        }
    }

    public BallistaType type()  { return type; }
    public Interaction anchor() { return anchor; }
    public UUID id()            { return anchor.getUniqueId(); }
    public Entity seat()        { return seat; }
    public boolean loaded()     { return loaded; }
    public boolean rocketLoaded() { return loaded && ammo != null && ammo.getType() == Material.FIREWORK_ROCKET; }
    public float elevation()    { return elevation; }
    public Phase phase()        { return phase; }

    public static Ballista spawn(BallistaType type, Location loc, float yaw, UUID owner) {
        World world = loc.getWorld();
        Location base = loc.clone();
        base.setYaw(0f);
        base.setPitch(0f);

        Interaction anchor = world.spawn(base, Interaction.class, e -> {
            e.setInteractionWidth(type.hitboxWidth);
            e.setInteractionHeight(type.hitboxHeight);
            e.setResponsive(true);
            e.setPersistent(true);
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(Cannon.KEY_TYPE, PersistentDataType.STRING, type.id);
            pdc.set(Cannon.KEY_ROLE, PersistentDataType.STRING, ROLE);
            pdc.set(Cannon.KEY_YAW, PersistentDataType.FLOAT, yaw);
            pdc.set(KEY_AIM, PersistentDataType.FLOAT, yaw);
            pdc.set(Cannon.KEY_ELEVATION, PersistentDataType.FLOAT, type.restElevation);
            if (owner != null) pdc.set(Cannon.KEY_OWNER, PersistentDataType.STRING, owner.toString());
        });

        Ballista b = new Ballista(type, anchor);
        Foundation.lay(anchor);
        for (int i = 0; i < b.rig.pieces.size(); i++) {
            BallistaRig.Piece p = b.rig.pieces.get(i);
            b.pieces.put(p.name(), b.spawnDisplay(p.name(), pieceStack(p, i)));
        }
        b.arrowA = b.spawnDisplay("arrow_a", null);
        b.arrowB = b.spawnDisplay("arrow_b", null);
        b.buildSeat();
        b.render();
        return b;
    }

    private static ItemStack pieceStack(BallistaRig.Piece p, int index) {
        if (!plugin.customModels()) return new ItemStack(plugin.debugBlock(index));
        ItemStack stack = new ItemStack(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(p.model()));
        return stack;
    }

    private ItemDisplay spawnDisplay(String bone, ItemStack stack) {
        Location at = anchor.getLocation();
        at.setYaw(0f);
        at.setPitch(0f);
        return anchor.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setPersistent(true);
            e.setViewRange(2.5f);
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(Cannon.KEY_ROLE, PersistentDataType.STRING, "display");
            pdc.set(Cannon.KEY_BONE, PersistentDataType.STRING, bone);
            pdc.set(Cannon.KEY_OWNER, PersistentDataType.STRING, anchor.getUniqueId().toString());
        });
    }

    private void buildSeat() {
        Location at = seatHome();
        seat = anchor.getWorld().spawn(at, ArmorStand.class, e -> {
            e.setMarker(true);
            e.setInvisible(true);
            e.setGravity(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setPersistent(true);
            e.setBasePlate(false);
            e.setCollidable(false);
            e.getPersistentDataContainer().set(Cannon.KEY_ROLE, PersistentDataType.STRING, "seat");
            e.getPersistentDataContainer()
             .set(Cannon.KEY_OWNER, PersistentDataType.STRING, anchor.getUniqueId().toString());
        });
        lastSeatYaw = aimYaw;
    }

    private Location seatHome() {
        Vector3f v = new Matrix4f().rotateY((float) Math.toRadians(180f - aimYaw))
                .transformPosition(new Vector3f(type.seat));
        Location at = anchor.getLocation().add(v.x, v.y, v.z);
        at.setYaw(aimYaw);
        at.setPitch(0f);
        return at;
    }

    public void mount(Player player) {
        if (seat != null && seat.isValid() && seat.getPassengers().isEmpty()) {
            Cannon.hideHearts(seat);
            seat.addPassenger(player);
        }
    }

    public boolean isRider(Player player) {
        return seat != null && player.getVehicle() != null
                && player.getVehicle().getUniqueId().equals(seat.getUniqueId());
    }

    private float worldYawRad() {
        return (float) Math.toRadians(180f - baseYaw);
    }

    private Matrix4f[] pose(float t) {
        return rig.pose(t, baseYaw - aimYaw, elevation - type.restElevation);
    }

    public void render() {
        Matrix4f[] world = pose(time);
        float yaw = worldYawRad();
        for (BallistaRig.Piece p : rig.pieces) {
            ItemDisplay d = pieces.get(p.name());
            if (d != null && d.isValid()) apply(d, rig.pieceMatrix(p, world, yaw));
        }
        boolean show = loaded && !released;
        Matrix4f sled = rig.boneFrame(rig.sled, world, yaw);
        if (show && ammoType() == Material.FIREWORK_ROCKET) {
            showArrow(arrowA, Material.FIREWORK_ROCKET, rocketMatrix(sled));
            showArrow(arrowB, null, null);
        } else {
            Material want = show ? Material.ARROW : null;
            showArrow(arrowA, want, arrowMatrix(sled, false));
            showArrow(arrowB, want, arrowMatrix(sled, true));
        }
        dirty = false;
    }

    private Matrix4f arrowMatrix(Matrix4f sledFrame, boolean crossed) {
        Vector3f c = type.arrowCenter;
        return new Matrix4f(sledFrame).translate(c.x, c.y, c.z).scale(16f)
                .mul(boltMatrix(crossed, type.arrowScale));
    }

    public static Matrix4f boltMatrix(boolean crossed, float scale) {
        Matrix4f m = new Matrix4f();
        if (crossed) m.rotateZ((float) Math.toRadians(90));
        return m.rotateY((float) Math.toRadians(-90))
                .rotateZ((float) Math.toRadians(45))
                .scale(scale);
    }

    private Matrix4f rocketMatrix(Matrix4f sledFrame) {
        Vector3f c = type.arrowCenter;
        return new Matrix4f(sledFrame).translate(c.x, c.y, c.z).scale(16f).mul(rocketSprite(type.arrowScale));
    }

    // rocket texture points up not diagonal
    public static Matrix4f rocketSprite(float scale) {
        return new Matrix4f().rotateY((float) Math.toRadians(-90))
                .rotateX((float) Math.toRadians(90))
                .rotateZ((float) Math.toRadians(90))
                .scale(scale);
    }

    private void showArrow(ItemDisplay d, Material want, Matrix4f m) {
        if (d == null || !d.isValid()) return;
        ItemStack held = d.getItemStack();
        Material has = held == null || held.getType().isAir() ? null : held.getType();
        if (want != null && has == null) apply(d, m, 0);
        if (want != has) d.setItemStack(want == null ? null : new ItemStack(want));
        if (want != null && has != null) apply(d, m, 1);
    }

    private static void apply(ItemDisplay d, Matrix4f m) {
        apply(d, m, 1);
    }

    private static void apply(ItemDisplay d, Matrix4f m, int ticks) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformationMatrix(m);
    }

    public void aimAt(Player rider) {
        float yaw = rider.getLocation().getYaw();
        float elev = Math.max(type.minElevation, Math.min(type.maxElevation, -rider.getLocation().getPitch()));
        if (Math.abs(Cannon.angleDelta(yaw, aimYaw)) < 0.2f && Math.abs(elev - elevation) < 0.2f) return;
        float step = plugin.turnStep();
        aimYaw = Cannon.wrap(aimYaw + Cannon.approach(Cannon.angleDelta(yaw, aimYaw), step));
        elevation += Cannon.approach(elev - elevation, step);
        anchor.getPersistentDataContainer().set(KEY_AIM, PersistentDataType.FLOAT, aimYaw);
        anchor.getPersistentDataContainer().set(Cannon.KEY_ELEVATION, PersistentDataType.FLOAT, elevation);
        dirty = true;
    }

    public boolean canLoad() {
        return !loaded && phase == Phase.IDLE;
    }

    public boolean canFire() {
        return loaded && (phase == Phase.COCKED || phase == Phase.IDLE);
    }

    private Material ammoType() {
        return ammo != null ? ammo.getType() : Material.ARROW;
    }

    private static ItemStack readAmmo(PersistentDataContainer pdc) {
        byte[] data = pdc.get(KEY_AMMO_ITEM, PersistentDataType.BYTE_ARRAY);
        if (data != null) {
            try {
                return ItemStack.deserializeBytes(data);
            } catch (Exception ignored) {
            }
        }
        return new ItemStack(Material.ARROW);
    }

    public static boolean isAmmo(ItemStack it) {
        return it != null && (it.getType() == Material.ARROW || it.getType() == Material.FIREWORK_ROCKET);
    }

    public void load(ItemStack item) {
        if (!canLoad() || !isAmmo(item)) return;
        ammo = item.asOne();
        anchor.getPersistentDataContainer().set(KEY_AMMO_ITEM, PersistentDataType.BYTE_ARRAY, ammo.serializeAsBytes());
        setLoaded(true);
        released = false;
        if (plugin.ballistaWindOnLoad()) {
            phase = Phase.WINDING;
            time = 0f;
            sound(Sound.ITEM_CROSSBOW_LOADING_START, 1f, 0.6f);
        } else {
            sound(Sound.ITEM_CROSSBOW_LOADING_END, 1f, 0.6f);
        }
        dirty = true;
    }

    public void fire(Player who) {
        if (!canFire()) return;
        shooter = who != null ? who.getUniqueId() : null;
        released = false;
        time = phase == Phase.COCKED ? type.releaseAt : 0f;
        phase = Phase.RELEASING;
        if (time >= type.releaseAt) release();
        dirty = true;
    }

    public void tick(Player rider) {
        if (rider != null) aimAt(rider);
        switch (phase) {
            case WINDING -> {
                time = Math.min(type.windEnd, time + TICK);
                dirty = true;
                if (time >= type.windEnd) {
                    phase = Phase.COCKED;
                    sound(Sound.ITEM_CROSSBOW_LOADING_END, 1f, 0.6f);
                }
            }
            case RELEASING -> {
                time += TICK;
                if (!released && time >= type.releaseAt) release();
                dirty = true;
                if (time >= type.animEnd) {
                    time = 0f;
                    phase = Phase.IDLE;
                }
            }
            default -> { }
        }
        if (dirty) render();
        followSeat();
    }

    public boolean busy() {
        return phase == Phase.WINDING || phase == Phase.RELEASING;
    }

    private void release() {
        released = true;
        ItemStack shot = ammo != null ? ammo : new ItemStack(Material.ARROW);
        boolean rocket = shot.getType() == Material.FIREWORK_ROCKET;
        setLoaded(false);
        Matrix4f sled = rig.boneFrame(rig.sled, pose(type.releaseAt), worldYawRad());
        Vector3f c = sled.transformPosition(new Vector3f(type.arrowCenter));
        Vector3f d = sled.transformDirection(new Vector3f(0f, 0f, -1f)).normalize();
        Location start = anchor.getLocation().add(c.x, c.y, c.z);
        Vector straight = new Vector(d.x, d.y, d.z);
        Player who = shooter != null ? plugin.getServer().getPlayer(shooter) : null;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < type.bolts; i++) {
            Vector dir = i == 0 ? straight.clone() : scatter(straight, type.spread, rng);
            double speed = i == 0 ? type.speed : type.speed * (0.95 + rng.nextDouble() * 0.1);
            if (rocket) new BallistaRocket(start, dir, speed, type.arrowScale, shot, who, id()).runTaskTimer(plugin, 1L, 1L);
            else new BallistaBolt(plugin, start, dir, speed, type.arrowScale, who, id()).runTaskTimer(plugin, 1L, 1L);
        }
        sound(Sound.ITEM_CROSSBOW_SHOOT, 1.6f, 0.6f);
        sound(Sound.ENTITY_ARROW_SHOOT, 1.2f, 0.5f);
        if (type.bolts > 1) sound(Sound.ITEM_CROSSBOW_SHOOT, 1.4f, 0.75f);
        ammo = null;
    }

    private static Vector scatter(Vector dir, double spreadDegrees, ThreadLocalRandom rng) {
        Vector up = Math.abs(dir.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector right = dir.getCrossProduct(up).normalize();
        Vector side = right.getCrossProduct(dir).normalize();
        double max = Math.toRadians(spreadDegrees * 2.4);
        double a = Math.max(-max, Math.min(max, rng.nextGaussian() * Math.toRadians(spreadDegrees)));
        double b = Math.max(-max, Math.min(max, rng.nextGaussian() * Math.toRadians(spreadDegrees)));
        return dir.clone().add(right.multiply(Math.tan(a))).add(side.multiply(Math.tan(b))).normalize();
    }

    private void setLoaded(boolean value) {
        loaded = value;
        anchor.getPersistentDataContainer().set(KEY_LOADED, PersistentDataType.BYTE, (byte) (value ? 1 : 0));
    }

    private void followSeat() {
        if (seat == null || !seat.isValid()) return;
        if (!Float.isNaN(lastSeatYaw) && Math.abs(Cannon.angleDelta(aimYaw, lastSeatYaw)) < SEAT_FOLLOW_DEGREES) return;
        lastSeatYaw = aimYaw;
        Location at = seatHome();
        at.setYaw(seat.getLocation().getYaw());
        seat.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    private void sound(Sound s, float volume, float pitch) {
        anchor.getWorld().playSound(anchor.getLocation(), s, volume, pitch);
    }

    public void remove() {
        if (loaded && !released) {
            anchor.getWorld().dropItemNaturally(anchor.getLocation(), ammo != null ? ammo : new ItemStack(Material.ARROW));
        }
        Ohmic_flicker_the_goat.forget(this);
        Foundation.lift(anchor);
        for (ItemDisplay d : pieces.values()) if (d.isValid()) d.remove();
        if (arrowA != null && arrowA.isValid()) arrowA.remove();
        if (arrowB != null && arrowB.isValid()) arrowB.remove();
        if (seat != null && seat.isValid()) {
            seat.eject();
            seat.remove();
        }
        if (anchor.isValid()) anchor.remove();
    }

    public static boolean isBallista(Entity e) {
        return e instanceof Interaction && ROLE.equals(
                e.getPersistentDataContainer().get(Cannon.KEY_ROLE, PersistentDataType.STRING));
    }

    public static Interaction anchorOf(Entity part) {
        if (isBallista(part)) return (Interaction) part;
        String owner = part.getPersistentDataContainer().get(Cannon.KEY_OWNER, PersistentDataType.STRING);
        if (owner == null) return null;
        try {
            Entity e = part.getWorld().getEntity(UUID.fromString(owner));
            return isBallista(e) ? (Interaction) e : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static Ballista adopt(Interaction anchor) {
        if (!isBallista(anchor)) return null;
        String typeId = anchor.getPersistentDataContainer().get(Cannon.KEY_TYPE, PersistentDataType.STRING);
        BallistaType type = BallistaType.byId(typeId);
        if (type == null || type.rig() == null) return null;
        Ballista b = new Ballista(type, anchor);
        String self = anchor.getUniqueId().toString();
        for (Entity e : anchor.getWorld().getNearbyEntities(anchor.getLocation(), 6, 6, 6)) {
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            if (!self.equals(pdc.get(Cannon.KEY_OWNER, PersistentDataType.STRING))) continue;
            String role = pdc.get(Cannon.KEY_ROLE, PersistentDataType.STRING);
            if ("seat".equals(role)) {
                b.seat = e;
            } else if ("display".equals(role) && e instanceof ItemDisplay d) {
                String bone = pdc.get(Cannon.KEY_BONE, PersistentDataType.STRING);
                if ("arrow_a".equals(bone)) b.arrowA = d;
                else if ("arrow_b".equals(bone)) b.arrowB = d;
                else if (bone != null) b.pieces.put(bone, d);
            }
        }
        b.lastSeatYaw = Float.NaN;
        b.render();
        return b;
    }
}
