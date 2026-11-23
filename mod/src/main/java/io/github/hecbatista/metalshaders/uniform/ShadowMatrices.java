package io.github.hecbatista.metalshaders.uniform;

import org.joml.Matrix4f;

/**
 * The shadow pass's light view, as Iris builds it (net.irisshaders.iris.shadows.ShadowMatrices
 * and ShadowRenderer; see NOTICE): a rotation about the player by the sky angle and the pack's
 * sunPathRotation, snapped to a grid of shadowIntervalSize so the map doesn't shimmer as the player
 * moves, and an orthographic box shadowDistance across each way. The projection is in OpenGL's
 * depth convention (-1..1), which is what packs are written against.
 */
public final class ShadowMatrices {
    /** The pack's shadow directives (PackShadowDirectives defaults until the pack is read). */
    public record Directives(int resolution, float distance, float nearPlane, float farPlane, float intervalSize, float sunPathRotation) {
        /** Iris's defaults: 1024, 160, ShadowMatrices.NEAR/FAR, 2, and no sun path tilt. */
        public static final Directives DEFAULT = new Directives(1024, 160.0f, -100.05f, 156.0f, 2.0f, 0.0f);
    }

    private static volatile Directives directives = Directives.DEFAULT;

    private ShadowMatrices() {
    }

    public static void setDirectives(Directives d) {
        directives = d;
    }

    public static Directives directives() {
        return directives;
    }

    /** ShadowMatrices.createModelViewMatrix: baseline rotation, then the grid snap. */
    public static Matrix4f modelView(float shadowAngle, double cameraX, double cameraY, double cameraZ) {
        Directives d = directives;
        float skyAngle = shadowAngle < 0.25f ? shadowAngle + 0.75f : shadowAngle - 0.25f;
        Matrix4f m = new Matrix4f()
                .rotateX((float) Math.toRadians(90.0))
                .rotateZ((float) Math.toRadians(skyAngle * -360.0f))
                .rotateX((float) Math.toRadians(d.sunPathRotation()));
        float interval = d.intervalSize();
        if (Math.abs(interval) != 0.0f) {
            // Java's % keeps the sign, as Iris notes; the offsets reproduce its exact values.
            float half = interval / 2.0f;
            float ox = (float) cameraX % interval - half;
            float oy = (float) cameraY % interval - half;
            float oz = (float) cameraZ % interval - half;
            m.translate(ox, oy, oz);
        }
        return m;
    }

    /** ShadowMatrices.createOrthoMatrix(shadowDistance, near, far), in OpenGL's convention. */
    public static Matrix4f projection() {
        Directives d = directives;
        float size = d.distance() * 2.0f;
        return new Matrix4f().setOrthoSymmetric(size, size, d.nearPlane(), d.farPlane(), false);
    }
}
