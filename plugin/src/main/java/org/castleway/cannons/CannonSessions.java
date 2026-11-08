package org.castleway.cannons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

// aims the barrel at the rider view and draws the action bar
public final class CannonSessions {
    public static final class Session {
        final Cannon cannon;
        final AimState state;

        Session(Cannon cannon, AimState state) {
            this.cannon = cannon; this.state = state;
        }
        public Cannon cannon()  { return cannon; }
        public AimState state() { return state; }

        public AmmoInventory ammo() {
            return AmmoInventory.of(cannon);
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

        Cannon.hideHearts(cannon.seat());
        Session session = new Session(cannon, state);
        active.put(player.getUniqueId(), session);
    }

    public Session of(Player player) { return active.get(player.getUniqueId()); }

    public void end(Player player) {
        active.remove(player.getUniqueId());
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
                it.remove();
                continue;
            }
            followView(player, session);
            pinSeat(session);

            hud(player, session);
        }
    }

    private void followView(Player player, Session session) {
        AimState state = session.state;
        float yaw = AimState.clampTraverse(player.getLocation().getYaw(), state.baseYaw);
        float elevation = AimState.clamp(-player.getLocation().getPitch(), session.cannon.type());
        if (Math.abs(Cannon.angleDelta(yaw, state.yaw)) < 0.25f
                && Math.abs(elevation - state.elevation) < 0.25f) return;
        float step = plugin.turnStep();
        state.yaw = Cannon.wrap(state.yaw + Cannon.approach(Cannon.angleDelta(yaw, state.yaw), step));
        state.elevation += Cannon.approach(elevation - state.elevation, step);
        session.cannon.aim(state.yaw, state.elevation);
    }

    private void pinSeat(Session session) {
        var seat = session.cannon.seat();
        if (!(seat instanceof Horse) || !seat.isValid()) return;
        var home = session.cannon.seatHome();
        if (seat.getLocation().distanceSquared(home) > 0.01) {
            home.setYaw(seat.getLocation().getYaw());
            seat.teleport(home, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        seat.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    private void hud(Player player, Session session) {
        AimState st = session.state;
        AmmoInventory rack = session.ammo();
        int powder = rack.powder();
        String charge = powder > 0
                ? String.format("speed %d/%d  flame %d/%d  smoke %d/%d  misfire %.1f%%  -  press F to fire",
                        powder, AmmoInventory.MAX_POWDER, rack.flame(), AmmoInventory.MAX_FLAME,
                        rack.smoke(), AmmoInventory.MAX_SMOKE,
                        100 * rack.misfireChance(powder))
                : "no gunpowder - press E";
        NamedTextColor color = powder > 0 ? NamedTextColor.GOLD : NamedTextColor.WHITE;
        if (WeaponHealth.dismantling(session.cannon.anchor())) color = NamedTextColor.RED;
        player.sendActionBar(Component.text(
                String.format("elevation %5.1f\u00b0    %s    %s", st.elevation, charge,
                        WeaponHealth.hud(session.cannon.anchor())), color));
    }
}
