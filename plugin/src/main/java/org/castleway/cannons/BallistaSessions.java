package org.castleway.cannons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

// ticks ballistas and tracks riders
public final class BallistaSessions {
    private final CannonsPlugin plugin;
    private final Map<UUID, Ballista> live = new HashMap<>();
    private final Map<UUID, UUID> riders = new HashMap<>();

    public BallistaSessions(CannonsPlugin plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public Ballista get(Interaction anchor) {
        Ballista b = live.get(anchor.getUniqueId());
        if (b != null && b.anchor().isValid()) return b;
        b = Ballista.adopt(anchor);
        if (b != null) live.put(anchor.getUniqueId(), b);
        return b;
    }

    public void track(Ballista b) {
        live.put(b.id(), b);
    }

    public void forget(Ballista b) {
        live.remove(b.id());
        riders.values().removeIf(id -> id.equals(b.id()));
    }

    public void begin(Player player, Ballista b) {
        riders.put(player.getUniqueId(), b.id());
    }

    public void end(Player player) {
        riders.remove(player.getUniqueId());
    }

    public Ballista ridden(Player player) {
        UUID id = riders.get(player.getUniqueId());
        if (id == null) return null;
        Ballista b = live.get(id);
        if (b == null || !b.isRider(player)) {
            riders.remove(player.getUniqueId());
            return null;
        }
        return b;
    }

    public Iterable<Ballista> all() {
        return live.values();
    }

    private void tick() {
        Map<UUID, Player> riderOf = new HashMap<>();
        Iterator<Map.Entry<UUID, UUID>> it = riders.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> e = it.next();
            Player p = plugin.getServer().getPlayer(e.getKey());
            Ballista b = live.get(e.getValue());
            if (p == null || !p.isOnline() || b == null || !b.isRider(p)) {
                it.remove();
                continue;
            }
            riderOf.put(b.id(), p);
        }

        Iterator<Ballista> bs = live.values().iterator();
        while (bs.hasNext()) {
            Ballista b = bs.next();
            if (!b.anchor().isValid()) { bs.remove(); continue; }
            Player rider = riderOf.get(b.id());
            if (rider == null && !b.busy()) continue;
            b.tick(rider);
            if (rider != null) hud(rider, b);
        }
    }

    private void hud(Player p, Ballista b) {
        String state;
        NamedTextColor color = NamedTextColor.WHITE;
        String what = b.rocketLoaded() ? "rocket" : "arrow";
        if (b.canFire()) { state = what + " loaded - press F to fire"; color = NamedTextColor.GOLD; }
        else if (b.phase() == Ballista.Phase.WINDING) state = "winding";
        else if (b.busy()) state = "firing";
        else state = "right click with an arrow or rocket to load";
        if (WeaponHealth.dismantling(b.anchor())) color = NamedTextColor.RED;
        p.sendActionBar(Component.text(
                String.format("elevation %5.1f°    %s    %s", b.elevation(), state, WeaponHealth.hud(b.anchor())), color));
    }

    public static boolean ownedBy(Entity part, Ballista b) {
        Interaction a = Ballista.anchorOf(part);
        return a != null && a.getUniqueId().equals(b.id());
    }
}
