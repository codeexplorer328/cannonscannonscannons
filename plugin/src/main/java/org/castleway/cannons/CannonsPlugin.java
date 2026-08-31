package org.castleway.cannons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

public final class CannonsPlugin extends JavaPlugin {
    private CannonSessions sessions;
    private boolean customModels = true;
    private boolean horseSeat = true;
    private float modelForward = 180f;
    private BoneDef.RotationTuning rotationTuning = BoneDef.RotationTuning.DEFAULT;
    private java.util.List<Material> debugBlocks = java.util.List.of(Material.STONE);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        loadTuning();
        customModels = getConfig().getBoolean("custom-models", true);
        horseSeat = "horse".equalsIgnoreCase(getConfig().getString("seat", "horse"));
        java.util.List<Material> blocks = new java.util.ArrayList<>();
        for (String name : getConfig().getStringList("debug-blocks")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock()) blocks.add(m);
        }
        if (!blocks.isEmpty()) debugBlocks = blocks;
        if (!customModels) {
            getLogger().warning("custom-models is off - rendering with vanilla blocks. "
                    + "The resource pack is not being used.");
        }

        Cannon.initKeys(this);
        Cannon.attach(this);
        sessions = new CannonSessions(this);
        getServer().getPluginManager().registerEvents(new CannonListener(this), this);
        getLogger().info(String.format(
                "Cannons " + getPluginMeta().getVersion()
                + " enabled. charge 0..1 -> %.3f..%.3f blocks/tick (8..50 blocks at 45 deg)",
                CannonBall.MIN_VELOCITY, CannonBall.MAX_VELOCITY));
    }

    public CannonSessions sessions() { return sessions; }

    public boolean customModels() { return customModels; }
    public boolean horseSeat() { return horseSeat; }

    public BoneDef.RotationTuning rotationTuning() { return rotationTuning; }

    // old configs have rotation values that no longer make sense
    private static final int CONFIG_VERSION = 2;

    private void migrateConfig() {
        var c = getConfig();
        if (c.getInt("config-version", 1) >= CONFIG_VERSION) return;
        c.set("rotation.flip-x", false);
        c.set("rotation.flip-z", false);
        c.set("rotation.elevation-sign", 1.0);
        c.set("rotation.model-forward", 180.0);
        c.set("config-version", CONFIG_VERSION);
        saveConfig();
        getLogger().warning("config.yml rotation settings were from an older pack layout "
                + "and have been reset. make sure the resource pack matches this build.");
    }

    private void loadTuning() {
        var c = getConfig();
        rotationTuning = new BoneDef.RotationTuning(
                c.getBoolean("rotation.flip-x", false),
                c.getBoolean("rotation.flip-y", false),
                c.getBoolean("rotation.flip-z", false),
                (float) c.getDouble("rotation.elevation-sign", 1.0));
        modelForward = (float) c.getDouble("rotation.model-forward", 180.0);
    }

    public float modelForward() { return modelForward; }

    private final java.util.Map<java.util.UUID, Integer> calibrating = new java.util.HashMap<>();

    private static final String[] QUESTIONS = {
        "Stand BEHIND the cannon, where the seat is. Is the muzzle pointing AWAY from you?",
        "Look at the seat. Does the seat BACK sit flush against the seat BASE (no gap)?",
        "Sit in it and look UP. Does the barrel RISE?"
    };

    private void calibrate(Player p, String answer) {
        Integer step = calibrating.get(p.getUniqueId());
        if (answer == null) {
            calibrating.put(p.getUniqueId(), 0);
            ask(p, 0);
            return;
        }
        if (step == null) { p.sendMessage(Component.text("run /cannon calibrate first")); return; }
        boolean yes = answer.equalsIgnoreCase("yes");
        var c = getConfig();
        switch (step) {
            case 0 -> { if (!yes) c.set("rotation.model-forward",
                    (c.getDouble("rotation.model-forward", 180) + 180) % 360); }
            case 1 -> { if (!yes) {
                    c.set("rotation.flip-x", !c.getBoolean("rotation.flip-x", false));
                    c.set("rotation.elevation-sign", -c.getDouble("rotation.elevation-sign", 1)); } }
            case 2 -> { if (!yes) c.set("rotation.elevation-sign",
                    -c.getDouble("rotation.elevation-sign", 1)); }
        }
        saveConfig();
        int n = reloadAll();
        if (step + 1 < QUESTIONS.length) {
            calibrating.put(p.getUniqueId(), step + 1);
            p.sendMessage(Component.text("saved. re-placed " + n + " cannon(s). next:", NamedTextColor.GRAY));
            ask(p, step + 1);
        } else {
            calibrating.remove(p.getUniqueId());
            p.sendMessage(Component.text("calibration done. settings are in config.yml.", NamedTextColor.GREEN));
        }
    }

    private void ask(Player p, int step) {
        p.sendMessage(Component.text("[" + (step + 1) + "/" + QUESTIONS.length + "] " + QUESTIONS[step], NamedTextColor.AQUA));
        p.sendMessage(Component.text("   ")
                .append(Component.text("[ YES ]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/cannon calibrate yes")))
                .append(Component.text("    "))
                .append(Component.text("[ NO ]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/cannon calibrate no"))));
    }

    private int reloadAll() {
        reloadConfig();
        loadTuning();
        horseSeat = "horse".equalsIgnoreCase(getConfig().getString("seat", "horse"));
        int n = 0;
        for (var world : getServer().getWorlds()) {
            for (var e : world.getEntitiesByClass(Interaction.class)) {
                if (!"anchor".equals(e.getPersistentDataContainer()
                        .get(Cannon.KEY_ROLE, PersistentDataType.STRING))) continue;
                Cannon c = Cannon.adopt(e);
                if (c != null) { c.refresh(); n++; }
            }
        }
        return n;
    }

    public Material debugBlock(int boneIndex) {
        return debugBlocks.get(boneIndex % debugBlocks.size());
    }

    public void fire(Cannon cannon, Player shooter, Material block, AimState state) {
        double velocity = CannonBall.MIN_VELOCITY
                + state.charge * (CannonBall.MAX_VELOCITY - CannonBall.MIN_VELOCITY);

        Location muzzle = cannon.muzzle(state.elevation);
        Vector direction = cannon.aimDirection(state.elevation);

        CannonBall ball = new CannonBall(
                this, muzzle, direction, velocity, block, shooter, cannon.id());
        ball.runTaskTimer(this, 1L, 1L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("/cannon give <cannon|makeshift> | remove | reload | calibrate"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give" -> {
                String id = args.length > 1 ? args[1].toLowerCase() : "cannon";
                CannonType type = id.startsWith("make")
                        ? CannonType.MAKESHIFT : CannonType.CANNON;
                Location at = player.getLocation();
                float yaw = Math.round(at.getYaw() / 90f) * 90f;
                Cannon.spawn(type, at.getBlock().getLocation().add(0.5, 0, 0.5),
                        yaw, player.getUniqueId());
                player.sendMessage(Component.text("Spawned " + type.displayName));
            }
            case "remove" -> {
                Interaction nearest = null;
                double best = Double.MAX_VALUE;
                for (var e : player.getNearbyEntities(8, 8, 8)) {
                    if (!(e instanceof Interaction i)) continue;
                    if (!"anchor".equals(i.getPersistentDataContainer()
                            .get(Cannon.KEY_ROLE, PersistentDataType.STRING))) continue;
                    double d = e.getLocation().distanceSquared(player.getLocation());
                    if (d < best) { best = d; nearest = i; }
                }
                if (nearest == null) {
                    player.sendMessage(Component.text("No cannon within 8 blocks."));
                    return true;
                }
                Cannon cannon = Cannon.adopt(nearest);
                if (cannon != null) cannon.remove();
                player.sendMessage(Component.text("Removed."));
            }
            case "calibrate" -> calibrate(player, args.length > 1 ? args[1] : null);
            case "reload" -> {
                int n = reloadAll();
                player.sendMessage(Component.text("Reloaded config, re-placed " + n + " cannon(s)."));
            }
            default -> player.sendMessage(Component.text("Unknown subcommand."));
        }
        return true;
    }
}
