package org.castleway.cannons;

public final class AimState {
    public static final float MIN_ELEVATION = -5f;
    public static final float HEAVY_MIN_ELEVATION = -30f;
    public static final float MAX_ELEVATION = 60f;

    public static final float MAX_TRAVERSE = 180f;

    public float baseYaw;
    public float yaw;
    public float elevation = 0f;

    public static float clamp(float elevation) {
        return clamp(elevation, MIN_ELEVATION);
    }

    public static float clamp(float elevation, CannonType type) {
        return clamp(elevation, type == CannonType.CANNON ? HEAVY_MIN_ELEVATION : MIN_ELEVATION);
    }

    private static float clamp(float elevation, float min) {
        return Math.max(min, Math.min(MAX_ELEVATION, elevation));
    }

    public static float clampTraverse(float requested, float baseYaw) {
        if (MAX_TRAVERSE >= 180f) return requested;
        float delta = Cannon.angleDelta(requested, baseYaw);
        float limited = Math.max(-MAX_TRAVERSE, Math.min(MAX_TRAVERSE, delta));
        return baseYaw + limited;
    }
}
