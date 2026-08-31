package org.castleway.cannons;

import org.bukkit.util.Vector;

import java.util.List;

public enum CannonType {
    CANNON("cannon", "Heavy Cannon",
            new Vector(0.0, 1.90, 1.70),      // seat
            new Vector(0.0, 2.00, -2.55),     // muzzle
            "bone_cannon", true,
            2.6, 3.1,
            List.of(
                new BoneDef("bone_base", "cannons:cannon_bone_base",
                        new Vector(0.0, 0.0, 0.0), 0.0f, 0.0f, 0.0f, 1.0f, null),
                new BoneDef("bone_rot", "cannons:cannon_bone_rot",
                        new Vector(0.0, 0.3125, 0.0), 0.0f, 0.0f, 0.0f, 2.0f, "bone_base"),
                new BoneDef("bone_cannon", "cannons:cannon_bone_cannon",
                        new Vector(0.0, 2.0, 0.0), 7.5f, 0.0f, 0.0f, 2.0f, "bone_rot"),
                new BoneDef("bone_free1", "cannons:cannon_bone_free1",
                        new Vector(0.0, 1.527016, 1.198517), -2.5f, 0.0f, 0.0f, 1.0f, "bone_cannon"),
                new BoneDef("bone_free2", "cannons:cannon_bone_free2",
                        new Vector(0.0, 1.7416, 1.814743), 2.5f, 0.0f, 0.0f, 1.0f, "bone_cannon")
            )),

    MAKESHIFT("makeshift_cannon", "Makeshift Cannon",
            new Vector(0.0, 0.60, 1.70),
            new Vector(0.0, 1.00, -2.55),
            "bone_cannon", false,
            2.2, 1.8,
            List.of(
                new BoneDef("bone_base", "cannons:makeshift_cannon_bone_base",
                        new Vector(0.0, 0.0, 0.0), 0.0f, 0.0f, 0.0f, 1.0f, null),
                new BoneDef("bone_cannon", "cannons:makeshift_cannon_bone_cannon",
                        new Vector(0.0, 0.563451, -0.021789), 5.0f, 0.0f, 0.0f, 2.0f, "bone_base"),
                new BoneDef("bone_free1", "cannons:makeshift_cannon_bone_free1",
                        new Vector(-0.3125, 0.595373, 1.047563), 15.0f, 0.0f, 0.0f, 1.0f, "bone_cannon"),
                new BoneDef("bone_free2", "cannons:makeshift_cannon_bone_free2",
                        new Vector(0.3125, 0.595373, 1.047563), 15.0f, 0.0f, 0.0f, 1.0f, "bone_cannon"),
                new BoneDef("bone_free3", "cannons:makeshift_cannon_bone_free3",
                        new Vector(0.3125, 0.776484, 1.096091), 15.0f, 0.0f, 0.0f, 1.0f, "bone_cannon"),
                new BoneDef("bone_free4", "cannons:makeshift_cannon_bone_free4",
                        new Vector(-0.3125, 0.776484, 1.096091), 15.0f, 0.0f, 0.0f, 1.0f, "bone_cannon"),
                new BoneDef("bone_free5", "cannons:makeshift_cannon_bone_free5",
                        new Vector(0.90625, 0.0, 0.5), 2.49762f, -44.94549f, -3.53329f, 1.0f, "bone_base"),
                new BoneDef("bone_free6", "cannons:makeshift_cannon_bone_free6",
                        new Vector(-0.90625, 0.0, 0.5), 2.49762f, 44.94549f, 3.53329f, 1.0f, "bone_base"),
                new BoneDef("bone_free7", "cannons:makeshift_cannon_bone_free7",
                        new Vector(0.90625, 0.0, -0.5), -2.49762f, 44.94549f, -3.53329f, 1.0f, "bone_base"),
                new BoneDef("bone_free8", "cannons:makeshift_cannon_bone_free8",
                        new Vector(-0.90625, 0.0, -0.5), -2.49762f, -44.94549f, 3.53329f, 1.0f, "bone_base")
            ));


    public final String id;
    public final String displayName;
    public final Vector seatOffset;
    public final Vector muzzleOffset;
    public final String barrelBone;
    public final boolean seatOnBarrel;
    public final double width, height;
    public final List<BoneDef> bones;

    CannonType(String id, String displayName, Vector seat, Vector muzzle,
               String barrelBone, boolean seatOnBarrel,
               double width, double height, List<BoneDef> bones) {
        this.id = id; this.displayName = displayName;
        this.seatOffset = seat; this.muzzleOffset = muzzle;
        this.barrelBone = barrelBone;
        this.seatOnBarrel = seatOnBarrel;
        this.width = width; this.height = height;
        this.bones = bones;
    }

    public BoneDef bone(String name) {
        for (BoneDef b : bones) if (b.name().equals(name)) return b;
        return null;
    }

    public boolean underBarrel(BoneDef b) {
        for (String n = b.name(); n != null; ) {
            if (n.equals(barrelBone)) return true;
            BoneDef p = bone(n);
            n = p == null ? null : p.parent();
        }
        return false;
    }

    public static CannonType byId(String id) {
        for (CannonType t : values()) if (t.id.equals(id)) return t;
        return null;
    }
}
