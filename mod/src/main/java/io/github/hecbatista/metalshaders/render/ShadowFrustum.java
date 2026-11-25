package io.github.hecbatista.metalshaders.render;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The shadow pass's tight frustum: only sections that can cast a shadow onto something the camera
 * sees. Adapted from Iris's AdvancedShadowCullingFrustum, BaseClippingPlanes and
 * NeighboringPlaneSet (LGPL-3.0; see NOTICE), which follow L. Spiro's "Tightly Culling Shadow
 * Casters for Directional Lights".
 * <p>
 * The idea: with the light coming from one direction, a block can only shade what the camera sees
 * if it lies in the camera's frustum swept toward the light. So the frustum keeps:
 * <ul>
 *   <li>the camera frustum's "back" planes, those whose inward normal points toward the light (a
 *       caster beyond them, on the far side from the light, can't reach the view), and</li>
 *   <li>an "edge" plane along each silhouette edge between a back plane and a front plane, parallel
 *       to the light, which closes the swept volume at the sides.</li>
 * </ul>
 * On top of Iris, the section must also overlap the shadow map's own box ({@link ShadowCulling.LightBox}):
 * geometry outside it can't land in the map, and the box is what Luxium culled with before (7c).
 * <p>
 * Positions are relative to the camera, as Sodium's viewport passes them.
 */
final class ShadowFrustum implements Frustum {
    /** Sodium pads a section's half size (8) by its margin (SimpleFrustum: 9.125); Iris uses the same. */
    private static final float SECTION_HALF_SIZE = 9.125f;
    /** Each base plane's four neighbours (Iris NeighboringPlaneSet): ±X planes border Y and Z, and so on. */
    private static final int[][] NEIGHBOURS = {{2, 3, 4, 5}, {0, 1, 4, 5}, {0, 1, 2, 3}};

    private final float[][] planes = new float[13][];
    private int planeCount;
    private final ShadowCulling.LightBox box;

    /**
     * @param cameraClip    gbufferProjection × gbufferModelView (OpenGL depth, camera-relative)
     * @param towardLight   unit vector from the camera toward the sun or moon, in world axes
     * @param box           the shadow map's box, also required (null to skip)
     */
    ShadowFrustum(Matrix4fc cameraClip, Vector3f towardLight, ShadowCulling.LightBox box) {
        this.box = box;
        Vector4f[] base = basePlanes(cameraClip);
        boolean[] back = new boolean[6];
        for (int i = 0; i < 6; i++) {
            float dot = base[i].x * towardLight.x + base[i].y * towardLight.y + base[i].z * towardLight.z;
            back[i] = dot > 0f;
            if (dot >= 0f) {
                add(base[i].x, base[i].y, base[i].z, base[i].w);
            }
        }
        for (int i = 0; i < 6; i++) {
            if (!back[i]) {
                continue;
            }
            for (int n : NEIGHBOURS[i >>> 1]) {
                if (!back[n]) {
                    addEdgePlane(base[i], base[n], towardLight);
                }
            }
        }
    }

    /**
     * The six clip planes of {@code clip} as (a, b, c, d) with a·x + b·y + c·z + d ≥ 0 inside, each
     * normalized as a 4-vector (Iris BaseClippingPlanes): left, right, bottom, top, far, near.
     */
    static Vector4f[] basePlanes(Matrix4fc clip) {
        Matrix4f t = new Matrix4f(clip).transpose();
        float[][] dirs = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        Vector4f[] out = new Vector4f[6];
        for (int i = 0; i < 6; i++) {
            Vector4f v = new Vector4f(dirs[i][0], dirs[i][1], dirs[i][2], 1f).mul(t);
            out[i] = v.normalize();
        }
        return out;
    }

    private void add(float a, float b, float c, float d) {
        planes[planeCount++] = new float[]{a, b, c, d};
    }

    /** The plane through the line where {@code backPlane} meets {@code frontPlane}, parallel to the light. */
    private void addEdgePlane(Vector4f backPlane, Vector4f frontPlane, Vector3f towardLight) {
        Vector3f backN = new Vector3f(backPlane.x, backPlane.y, backPlane.z);
        Vector3f frontN = new Vector3f(frontPlane.x, frontPlane.y, frontPlane.z);
        Vector3f line = new Vector3f(backN).cross(frontN);
        float lineLengthSq = line.lengthSquared();
        if (lineLengthSq < 1e-12f) {
            return;   // parallel planes have no common edge
        }
        Vector3f normal = new Vector3f(line).cross(towardLight);
        // A point on the line (Graphics Gems 1, "Intersection of 2 planes"; as Iris does it).
        Vector3f lxb = new Vector3f(line).cross(backN).mul(-frontPlane.w);
        Vector3f fxl = new Vector3f(frontN).cross(line).mul(-backPlane.w);
        Vector3f point = lxb.add(fxl).mul(1f / lineLengthSq);
        add(normal.x, normal.y, normal.z, -normal.dot(point));
    }

    int planeCount() {
        return planeCount;
    }

    /** JOML FrustumIntersection codes as Sodium reads them: -2 inside, -1 crossing, 0 outside. */
    @Override
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int boxResult = box == null ? -2 : box.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
        if (boxResult == 0) {
            return 0;
        }
        boolean inside = true;
        for (int i = 0; i < planeCount; i++) {
            float[] p = planes[i];
            // The corner farthest along the normal: if even it is outside, the whole box is.
            float far = p[0] * (p[0] < 0 ? minX : maxX) + p[1] * (p[1] < 0 ? minY : maxY) + p[2] * (p[2] < 0 ? minZ : maxZ);
            if (far < -p[3]) {
                return 0;
            }
            float near = p[0] * (p[0] < 0 ? maxX : minX) + p[1] * (p[1] < 0 ? maxY : minY) + p[2] * (p[2] < 0 ? maxZ : minZ);
            inside &= near + p[3] >= 0;
        }
        return inside && boxResult == -2 ? -2 : -1;
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return intersectAab(minX, minY, minZ, maxX, maxY, maxZ) != 0;
    }

    @Override
    public boolean testSection(float x, float y, float z) {
        return testSectionExpanded(x, y, z, 0f);
    }

    @Override
    public boolean testSectionExpanded(float x, float y, float z, float extend) {
        float r = SECTION_HALF_SIZE + extend;
        return testAab(x - r, y - r, z - r, x + r, y + r, z + r);
    }
}
