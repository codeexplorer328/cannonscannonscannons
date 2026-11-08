package org.castleway.cannons;

import org.bukkit.util.Vector;
import org.joml.Quaternionf;

public record BoneDef(
        String name,
        String itemModel,
        Vector translation,
        float rotX, float rotY, float rotZ,
        float scale,
        String parent
) {
    public Quaternionf toQuaternion(RotationTuning t) {
        float x = t.flipX ? -rotX : rotX;
        float y = t.flipY ? -rotY : rotY;
        float z = t.flipZ ? -rotZ : rotZ;
        return new Quaternionf().rotationZYX(
                (float) Math.toRadians(z),
                (float) Math.toRadians(y),
                (float) Math.toRadians(x));
    }

    public record RotationTuning(boolean flipX, boolean flipY, boolean flipZ, float elevationSign) {
        public static final RotationTuning DEFAULT = new RotationTuning(false, false, false, 1f);
    }
}
