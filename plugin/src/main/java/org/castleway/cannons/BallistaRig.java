package org.castleway.cannons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// plays rig.py keyframes as a matrix per piece
public final class BallistaRig {
    public record Bone(String name, int parent, float[] origin, float[] rot) {}

    public record Piece(String name, int bone, String model,
                        float[] offset, float[] rot, float[] shift, float k) {}

    private record Channel(float[][] keys) {
        void sample(float t, float[] out) {
            float[][] k = keys;
            if (t <= k[0][0]) { copy(k[0], out); return; }
            if (t >= k[k.length - 1][0]) { copy(k[k.length - 1], out); return; }
            for (int i = 0; i < k.length - 1; i++) {
                float[] a = k[i], b = k[i + 1];
                if (t > b[0]) continue;
                float f = b[0] == a[0] ? 0f : (t - a[0]) / (b[0] - a[0]);
                for (int j = 0; j < 3; j++) out[j] = a[j + 1] + (b[j + 1] - a[j + 1]) * f;
                return;
            }
        }

        private static void copy(float[] row, float[] out) {
            out[0] = row[1]; out[1] = row[2]; out[2] = row[3];
        }
    }

    private static final float[] ZERO = {0f, 0f, 0f};
    private static final int ROT = 0, POS = 1, SCALE = 2;

    public final List<Bone> bones = new ArrayList<>();
    public final List<Piece> pieces = new ArrayList<>();
    private Channel[][] channels;
    public final int turret, rail, sled;

    private BallistaRig(JsonObject root, String animation, String turretBone,
                        String railBone, String sledBone) {
        for (JsonElement e : root.getAsJsonArray("bones")) {
            JsonObject o = e.getAsJsonObject();
            bones.add(new Bone(o.get("name").getAsString(), o.get("parent").getAsInt(),
                    vec(o.getAsJsonArray("origin")), vec(o.getAsJsonArray("rot"))));
        }
        for (JsonElement e : root.getAsJsonArray("pieces")) {
            JsonObject o = e.getAsJsonObject();
            pieces.add(new Piece(o.get("name").getAsString(), o.get("bone").getAsInt(),
                    o.get("model").getAsString(), vec(o.getAsJsonArray("offset")),
                    vec(o.getAsJsonArray("rot")), vec(o.getAsJsonArray("shift")),
                    o.get("k").getAsFloat()));
        }
        channels = new Channel[bones.size()][3];
        JsonObject anims = root.getAsJsonObject("animations");
        if (anims != null && anims.has(animation)) {
            JsonObject byBone = anims.getAsJsonObject(animation).getAsJsonObject("bones");
            for (int i = 0; i < bones.size(); i++) {
                JsonObject ch = byBone.getAsJsonObject(bones.get(i).name());
                if (ch == null) continue;
                channels[i][ROT] = channel(ch, "rotation");
                channels[i][POS] = channel(ch, "position");
                channels[i][SCALE] = channel(ch, "scale");
            }
        }
        turret = indexOf(turretBone);
        rail = indexOf(railBone);
        sled = indexOf(sledBone);
    }

    public static BallistaRig load(CannonsPlugin plugin, String resource, String animation,
                                   String turretBone, String railBone, String sledBone) {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource + " in the jar");
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return new BallistaRig(root, animation, turretBone, railBone, sledBone);
        } catch (IOException ex) {
            throw new IllegalStateException("could not read " + resource, ex);
        }
    }

    private int indexOf(String name) {
        for (int i = 0; i < bones.size(); i++) if (bones.get(i).name().equals(name)) return i;
        throw new IllegalStateException("rig has no bone called " + name);
    }

    private static Channel channel(JsonObject ch, String key) {
        JsonArray rows = ch.getAsJsonArray(key);
        if (rows == null || rows.isEmpty()) return null;
        float[][] keys = new float[rows.size()][];
        for (int i = 0; i < rows.size(); i++) keys[i] = vec(rows.get(i).getAsJsonArray());
        return new Channel(keys);
    }

    private static float[] vec(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    public Matrix4f[] pose(float t, float yawRelDeg, float pitchExtraDeg) {
        Matrix4f[] out = new Matrix4f[bones.size()];
        float[] rot = new float[3], pos = new float[3], scl = new float[3];
        for (int i = 0; i < bones.size(); i++) {
            Bone b = bones.get(i);
            sample(i, ROT, t, rot, 0f);
            sample(i, POS, t, pos, 0f);
            sample(i, SCALE, t, scl, 1f);
            float[] po = b.parent() >= 0 ? bones.get(b.parent()).origin() : ZERO;
            Matrix4f m = b.parent() >= 0 ? new Matrix4f(out[b.parent()]) : new Matrix4f();
            m.translate(b.origin()[0] - po[0] + pos[0],
                        b.origin()[1] - po[1] + pos[1],
                        b.origin()[2] - po[2] + pos[2]);
            if (i == turret) m.rotateY((float) Math.toRadians(yawRelDeg));
            if (i == rail) m.rotateX((float) Math.toRadians(pitchExtraDeg));
            m.rotateZYX((float) Math.toRadians(b.rot()[2] + rot[2]),
                        (float) Math.toRadians(b.rot()[1] + rot[1]),
                        (float) Math.toRadians(b.rot()[0] + rot[0]));
            m.scale(scl[0], scl[1], scl[2]);
            out[i] = m;
        }
        return out;
    }

    private void sample(int bone, int kind, float t, float[] out, float fallback) {
        Channel c = channels[bone][kind];
        if (c == null) { out[0] = out[1] = out[2] = fallback; return; }
        c.sample(t, out);
    }

    public Matrix4f pieceMatrix(Piece p, Matrix4f[] world, float worldYawRad) {
        return new Matrix4f()
                .rotateY(worldYawRad)
                .scale(1f / 16f)
                .mul(world[p.bone()])
                .translate(p.offset()[0], p.offset()[1], p.offset()[2])
                .rotateZYX((float) Math.toRadians(p.rot()[2]),
                           (float) Math.toRadians(p.rot()[1]),
                           (float) Math.toRadians(p.rot()[0]))
                .scale(1f / p.k())
                .translate(-p.shift()[0], -p.shift()[1], -p.shift()[2])
                .scale(16f);
    }

    public Matrix4f boneFrame(int bone, Matrix4f[] world, float worldYawRad) {
        return new Matrix4f().rotateY(worldYawRad).scale(1f / 16f).mul(world[bone]);
    }
}
