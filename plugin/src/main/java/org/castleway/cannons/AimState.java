package org.castleway.cannons;

public final class AimState {

    public static final float MIN_ELEVATION = -5f;
    public static final float MAX_ELEVATION = 60f;

    // 180 or more means no limit
    public static final float MAX_TRAVERSE = 180f;

    public float baseYaw;
    public float yaw;
    public float elevation = 0f;

    public float charge = 0f;        // 0 to 1
    public boolean charged = false;
    public boolean charging = false; // stand seat only

    public void resetCharge() {
        charge = 0f;
        charged = false;
        charging = false;
    }

    public static float clamp(float elevation) {
        return Math.max(MIN_ELEVATION, Math.min(MAX_ELEVATION, elevation));
    }

    public static float clampTraverse(float requested, float baseYaw) {
        if (MAX_TRAVERSE >= 180f) return requested;
        float delta = Cannon.angleDelta(requested, baseYaw);
        float limited = Math.max(-MAX_TRAVERSE, Math.min(MAX_TRAVERSE, delta));
        return baseYaw + limited;
    }
}
