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

// plugin setup and the cannon command
public final class CannonsPlugin extends JavaPlugin {
    private CannonSessions sessions;
    private BallistaSessions ballistas;
    private boolean ballistaWindOnLoad = true;
    private float turnStep = 4.5f;
    private boolean customModels = true;
    private boolean horseSeat = true;
    private float modelForward = 180f;
    private BoneDef.RotationTuning rotationTuning = BoneDef.RotationTuning.DEFAULT;
    private java.util.List<Material> debugBlocks = java.util.List.of(Material.STONE);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadTuning();
        customModels = getConfig().getBoolean("custom-models", true);
        horseSeat = "horse".equalsIgnoreCase(getConfig().getString("seat", "horse"));
        ballistaWindOnLoad = getConfig().getBoolean("ballista-wind-on-load", true);
        turnStep = (float) Math.max(0.5, getConfig().getDouble("turn-speed", 90.0) / 20.0);
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
        Ballista.init(this);
        ballistas = new BallistaSessions(this);
        getServer().getPluginManager().registerEvents(new BallistaListener(this), this);
        WeaponHealth.init(this);
        Foundation.init(this);
        getServer().getPluginManager().registerEvents(new FoundationListener(), this);
        BallistaRocket.init(this);
        getServer().getPluginManager().registerEvents(new WeaponListener(), this);
        BuildPlan.init(this);
        getServer().getPluginManager().registerEvents(new BuildPlanListener(this), this);
        for (Player p : getServer().getOnlinePlayers()) p.discoverRecipes(BuildPlan.recipes());
        getLogger().info(String.format(
                "Cannons " + getPluginMeta().getVersion()
                + " enabled. charge 0..1 -> %.3f..%.3f blocks/tick (8..250 blocks at 45 deg)",
                CannonBall.MIN_VELOCITY, CannonBall.MAX_VELOCITY));
    }

    public CannonSessions sessions() { return sessions; }
    public BallistaSessions ballistas() { return ballistas; }
    public boolean ballistaWindOnLoad() { return ballistaWindOnLoad; }
    public float turnStep() { return turnStep; }

    public boolean customModels() { return customModels; }
    public boolean horseSeat() { return horseSeat; }

    public BoneDef.RotationTuning rotationTuning() { return rotationTuning; }

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
        ballistaWindOnLoad = getConfig().getBoolean("ballista-wind-on-load", true);
        turnStep = (float) Math.max(0.5, getConfig().getDouble("turn-speed", 90.0) / 20.0);
        int n = 0;
        for (var world : getServer().getWorlds()) {
            for (var e : world.getEntitiesByClass(Interaction.class)) {
                if (!"anchor".equals(e.getPersistentDataContainer()
                        .get(Cannon.KEY_ROLE, PersistentDataType.STRING))) continue;
                Cannon c = Cannon.adopt(e);
                if (c != null) { c.refresh(); n++; }
            }
            for (var e : world.getEntitiesByClass(Interaction.class)) {
                if (!Ballista.isBallista(e)) continue;
                Ballista b = ballistas.get(e);
                if (b != null) { b.render(); n++; }
            }
        }
        return n;
    }

    public Material debugBlock(int boneIndex) {
        return debugBlocks.get(boneIndex % debugBlocks.size());
    }

    public void fire(Cannon cannon, Player shooter, Material block, int powder, int flame, int smoke, float elevation) {
        double velocity = CannonBall.MIN_VELOCITY
                + (CannonBall.MAX_VELOCITY - CannonBall.MIN_VELOCITY) * powder / AmmoInventory.MAX_POWDER;
        double fire = AmmoInventory.fireChance(flame);

        Location muzzle = cannon.muzzle(elevation);
        Vector direction = cannon.aimDirection(elevation);

        CannonBall ball = new CannonBall(
                this, muzzle, direction, velocity, block, shooter, cannon.id(), fire, smoke);
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
            player.sendMessage(Component.text("/cannon give <cannon|makeshift|ballista|mounted> | plans | remove | reload | calibrate"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give" -> {
                String id = args.length > 1 ? args[1].toLowerCase() : "cannon";
                BallistaType bt = id.startsWith("mount") ? BallistaType.MOUNTED
                        : id.startsWith("bal") ? BallistaType.BALLISTA : null;
                if (bt != null) {
                    Location at = player.getLocation();
                    float yaw = Math.round(at.getYaw() / 90f) * 90f;
                    Ballista b = Ballista.spawn(bt, at.getBlock().getLocation().add(0.5, 0, 0.5),
                            yaw, player.getUniqueId());
                    ballistas.track(b);
                    player.sendMessage(Component.text("Spawned " + bt.displayName));
                    return true;
                }
                CannonType type = id.startsWith("make")
                        ? CannonType.MAKESHIFT : CannonType.CANNON;
                Location at = player.getLocation();
                float yaw = Math.round(at.getYaw() / 90f) * 90f;
                Cannon.spawn(type, at.getBlock().getLocation().add(0.5, 0, 0.5),
                        yaw, player.getUniqueId());
                player.sendMessage(Component.text("Spawned " + type.displayName));
            }
            case "plans" -> {
                for (BuildPlan.Kind k : BuildPlan.KINDS) {
                    player.getInventory().addItem(BuildPlan.item(k)).values()
                            .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                }
                player.sendMessage(Component.text("Gave one of each build plan"));
            }
            case "remove" -> {
                Interaction nearest = null;
                double best = Double.MAX_VALUE;
                for (var e : player.getNearbyEntities(8, 8, 8)) {
                    if (!(e instanceof Interaction i)) continue;
                    if (!"anchor".equals(i.getPersistentDataContainer()
                            .get(Cannon.KEY_ROLE, PersistentDataType.STRING))
                            && !Ballista.isBallista(i) && !BuildPlan.isZone(i)) continue;
                    double d = e.getLocation().distanceSquared(player.getLocation());
                    if (d < best) { best = d; nearest = i; }
                }
                if (nearest == null) {
                    player.sendMessage(Component.text("No cannon within 8 blocks."));
                    return true;
                }
                if (BuildPlan.isZone(nearest)) {
                    BuildPlan.clear(nearest);
                    player.sendMessage(Component.text("Removed."));
                    return true;
                }
                if (Ballista.isBallista(nearest)) {
                    Ballista b = ballistas.get(nearest);
                    if (b != null) { ballistas.forget(b); b.remove(); }
                    player.sendMessage(Component.text("Removed."));
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
