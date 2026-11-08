package org.castleway.cannons;

import org.joml.Vector3f;

import java.util.List;

// settings for each ballista
public final class BallistaType {
    public static final BallistaType BALLISTA = new BallistaType(
            "ballista", "Ballista", "ballista.json", "animation.model.new",
            "group3", "group4", "group9",
            15f, -30f, 60f,
            1.0f, 1.5f, 2.0f,
            new Vector3f(0f, 1.1f, 2.2f),
            new Vector3f(0f, 3f, -14f), 1.2f,
            2.2f, 2.6f,
            1, 3.5, 0.0);

    public static final BallistaType MOUNTED = new BallistaType(
            "mounted_ballista", "Mounted Ballista", "mounted_ballista.json", "animation",
            "group2", "group4", "group9",
            0f, -30f, 40f,
            0.58333f, 0.91667f, 1.125f,
            new Vector3f(0f, 1.2f, 2.6f),
            new Vector3f(0f, 2f, -13f), 1.2f,
            2.6f, 2.6f,
            5, 3.5 * 1.5, 2.5);

    public static final List<BallistaType> ALL = List.of(BALLISTA, MOUNTED);

    public final String id, displayName, resource, animation;
    public final String turretBone, railBone, sledBone;
    public final float restElevation, minElevation, maxElevation;
    public final float windEnd, releaseAt, animEnd;
    public final Vector3f seat;
    public final Vector3f arrowCenter;
    public final float arrowScale;
    public final float hitboxWidth, hitboxHeight;
    public final int bolts;
    public final double speed;
    public final double spread;

    BallistaRig rig;

    private BallistaType(String id, String displayName, String resource, String animation,
                         String turretBone, String railBone, String sledBone,
                         float restElevation, float minElevation, float maxElevation,
                         float windEnd, float releaseAt, float animEnd,
                         Vector3f seat, Vector3f arrowCenter, float arrowScale,
                         float hitboxWidth, float hitboxHeight,
                         int bolts, double speed, double spread) {
        this.id = id; this.displayName = displayName; this.resource = resource; this.animation = animation;
        this.turretBone = turretBone; this.railBone = railBone; this.sledBone = sledBone;
        this.restElevation = restElevation; this.minElevation = minElevation; this.maxElevation = maxElevation;
        this.windEnd = windEnd; this.releaseAt = releaseAt; this.animEnd = animEnd;
        this.seat = seat; this.arrowCenter = arrowCenter; this.arrowScale = arrowScale;
        this.hitboxWidth = hitboxWidth; this.hitboxHeight = hitboxHeight;
        this.bolts = bolts; this.speed = speed; this.spread = spread;
    }

    public BallistaRig rig() { return rig; }

    static void loadRigs(CannonsPlugin plugin) {
        for (BallistaType t : ALL) {
            t.rig = BallistaRig.load(plugin, t.resource, t.animation, t.turretBone, t.railBone, t.sledBone);
        }
    }

    public static BallistaType byId(String id) {
        for (BallistaType t : ALL) if (t.id.equals(id)) return t;
        return null;
    }
}
