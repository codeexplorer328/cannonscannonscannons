package org.castleway.cannons;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

// tracks who is riding what and aims the barrel every tick
public final class CannonSessions {

    private static final int CHARGE_TICKS = 30;

    public static final class Session {
        final Cannon cannon;
        final AimState state;
        final BossBar bar;       // stand seat only
        AmmoInventory ammo;
        ItemStack heldBefore;
        int slotBefore;

        Session(Cannon cannon, AimState state, BossBar bar) {
            this.cannon = cannon; this.state = state; this.bar = bar;
        }
        public Cannon cannon()  { return cannon; }
        public AimState state() { return state; }

        public AmmoInventory ammo() {
            if (ammo == null) ammo = new AmmoInventory(cannon);
            return ammo;
        }
    }

    private final CannonsPlugin plugin;
    private final Map<UUID, Session> active = new HashMap<>();

    public CannonSessions(CannonsPlugin plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void begin(Player player, Cannon cannon) {
        end(player);

        AimState state = new AimState();
        state.baseYaw = cannon.yaw();
        state.yaw = cannon.yaw();
        state.elevation = cannon.currentElevation();

        BossBar bar = null;
        if (!(cannon.seat() instanceof Horse)) {
            bar = BossBar.bossBar(Component.text("hold space to charge"),
                    0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            player.showBossBar(bar);
        }
        Session session = new Session(cannon, state, bar);
        giveLanyard(player, session);
        active.put(player.getUniqueId(), session);
    }

    // empty hand right clicks on air send nothing so the rider holds a stick
    private void giveLanyard(Player player, Session s) {
        var inv = player.getInventory();
        s.slotBefore = inv.getHeldItemSlot();
        s.heldBefore = inv.getItemInMainHand().clone();
        inv.setItemInMainHand(lanyard());
    }

    private void takeLanyard(Player player, Session s) {
        var inv = player.getInventory();
        ItemStack held = inv.getItem(s.slotBefore);
        if (isLanyard(held)) inv.setItem(s.slotBefore, s.heldBefore);
        for (int i = 0; i < inv.getSize(); i++) {
            if (isLanyard(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    public static ItemStack lanyard() {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Firing Lanyard", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(java.util.List.of(Component.text("right click to fire", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(Cannon.KEY_LANYARD, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    public static boolean isLanyard(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(Cannon.KEY_LANYARD, PersistentDataType.BYTE);
    }

    public Session of(Player player) { return active.get(player.getUniqueId()); }

    public void end(Player player) {
        Session s = active.remove(player.getUniqueId());
        if (s == null) return;
        if (s.bar != null) player.hideBossBar(s.bar);
        takeLanyard(player, s);
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Session>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Session session = entry.getValue();

            if (player == null || !player.isOnline()
                    || player.getVehicle() == null
                    || !session.cannon.anchor().isValid()) {
                if (player != null) {
                    if (session.bar != null) player.hideBossBar(session.bar);
                    takeLanyard(player, session);
                }
                it.remove();
                continue;
            }
            if (player.getInventory().getHeldItemSlot() != session.slotBefore) {
                player.getInventory().setHeldItemSlot(session.slotBefore);
            }
            if (!isLanyard(player.getInventory().getItemInMainHand())) {
                player.getInventory().setItemInMainHand(lanyard());
            }

            followView(player, session);
            pinSeat(session);

            if (session.bar != null) chargeFromInput(player, session);
            hud(player, session);
        }
    }

    private void followView(Player player, Session session) {
        AimState state = session.state;
        float yaw = AimState.clampTraverse(player.getLocation().getYaw(), state.baseYaw);
        float elevation = AimState.clamp(-player.getLocation().getPitch());
        if (Math.abs(Cannon.angleDelta(yaw, state.yaw)) < 0.25f
                && Math.abs(elevation - state.elevation) < 0.25f) return;
        state.yaw = yaw;
        state.elevation = elevation;
        session.cannon.aim(yaw, elevation);
    }

    private void pinSeat(Session session) {
        var seat = session.cannon.seat();
        if (!(seat instanceof Horse) || !seat.isValid()) return;
        var home = session.cannon.seatHome();
        if (seat.getLocation().distanceSquared(home) > 0.01) {
            home.setYaw(seat.getLocation().getYaw());
            seat.teleport(home);
        }
        seat.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    private void chargeFromInput(Player player, Session session) {
        AimState state = session.state;
        boolean jump = player.getCurrentInput().isJump();

        if (jump && !state.charged) {
            if (!state.charging) { state.charging = true; state.charge = 0f; }
            state.charge = Math.min(1f, state.charge + 1f / CHARGE_TICKS);
            session.bar.color(state.charge >= 1f ? BossBar.Color.RED : BossBar.Color.YELLOW);
            paint(session, String.format("charging  %3.0f%%", state.charge * 100));
        } else if (!jump && state.charging) {
            state.charging = false;
            state.charged = true;
            player.playSound(player.getLocation(), Sound.BLOCK_PISTON_CONTRACT, 1f,
                    0.6f + state.charge * 0.8f);
            session.bar.color(BossBar.Color.GREEN);
            paint(session, String.format("charge set  %3.0f%%  -  right click to fire",
                    state.charge * 100));
        } else if (!state.charged) {
            session.bar.color(BossBar.Color.YELLOW);
            paint(session, "hold space to charge");
        }
    }

    private void hud(Player player, Session session) {
        AimState st = session.state;
        String charge = st.charged ? String.format("charge %3.0f%%  -  right click to fire", st.charge * 100)
                      : st.charging ? String.format("charging %3.0f%%", st.charge * 100)
                      : "hold space to charge";
        player.sendActionBar(Component.text(
                String.format("elevation %5.1f\u00b0    %s", st.elevation, charge),
                st.charged ? NamedTextColor.GOLD : NamedTextColor.WHITE));
    }

    private void paint(Session session, String text) {
        session.bar.progress(Math.max(0f, Math.min(1f, session.state.charge)));
        session.bar.name(Component.text(text));
    }

    public void setCharge(Player player, float power) {
        Session s = of(player);
        if (s == null) return;
        s.state.charge = Math.max(0f, Math.min(1f, power));
        s.state.charged = true;
        player.playSound(player.getLocation(), Sound.BLOCK_PISTON_CONTRACT, 1f,
                0.6f + s.state.charge * 0.8f);
    }

    public void afterFire(Player player) {
        Session s = of(player);
        if (s == null) return;
        s.state.resetCharge();
        if (s.bar != null) {
            s.bar.color(BossBar.Color.YELLOW);
            paint(s, "hold space to charge");
        }
    }
}
