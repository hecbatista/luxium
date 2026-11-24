package io.github.hecbatista.metalshaders.uniform;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.HashMap;
import java.util.Map;

/**
 * The camera matrices a pack reads, captured once per frame while the world is drawn
 * (GameRendererRenderMixin, at RenderSystem.setProjectionMatrix inside GameRenderer.renderLevel).
 * <p>
 * As Iris does it (MixinLevelRenderer.java:113-115, MatrixUniforms, MatrixUtils; see NOTICE):
 * gbufferModelView is the camera's rotation (positions are relative to the camera) with view
 * bobbing, hurt shake and the nausea spin in it; gbufferProjection is the game's plain projection,
 * turned from reversed-Z back into the OpenGL convention (GameRendererRenderMixin moves the bob
 * out of the projection, as Iris MixinModelViewBobbing does); each also comes inverted and as
 * last frame's value.
 */
public final class RenderMatrices {
    private final Map<String, Object> values = new HashMap<>();
    private Matrix4f previousModelView = new Matrix4f();
    private Matrix4f previousProjection = new Matrix4f();

    /**
     * Every name this class supplies once a frame has been drawn, so pack formulas that read them
     * can be accepted at load, before the first frame.
     */
    public static final java.util.Set<String> NAMES = java.util.Set.of(
            "gbufferModelView", "gbufferModelViewInverse", "gbufferPreviousModelView",
            "gbufferProjection", "gbufferProjectionInverse", "gbufferPreviousProjection",
            "sunPosition", "moonPosition", "shadowLightPosition", "upPosition");

    public Object get(String name) {
        return values.get(name);
    }

    /**
     * Iris's CelestialUniforms (see NOTICE), in view space from this frame's gbufferModelView:
     * the sky's fixed -90 degree turn about Y, the pack's sunPathRotation about Z, then the body's
     * angle about X, applied to (0, 100, 0, 1) and truncated to xyz. Iris's quirk is kept: the moon
     * also starts from +100 (getCelestialPosition ignores its y argument), so the moon's angle alone
     * puts it opposite the sun. upPosition skips the last two turns. shadowLightPosition is the sun
     * by day (sunAngle below half a turn), the moon by night.
     *
     * @param sunDegrees  EnvironmentAttributes.SUN_ANGLE this frame
     * @param moonDegrees EnvironmentAttributes.MOON_ANGLE this frame
     * @param day         Iris's isDay(): the sunAngle uniform (sunDegrees + 90, wrapped) below 180
     */
    public void celestial(float sunDegrees, float moonDegrees, boolean day, float sunPathRotation) {
        Matrix4f modelView = (Matrix4f) values.get("gbufferModelView");
        if (modelView == null) {
            return;
        }
        float[] sun = celestialPosition(modelView, sunPathRotation, sunDegrees);
        float[] moon = celestialPosition(modelView, sunPathRotation, moonDegrees);
        values.put("sunPosition", sun);
        values.put("moonPosition", moon);
        values.put("shadowLightPosition", day ? sun : moon);
        org.joml.Vector4f up = new Matrix4f(modelView).rotateY((float) Math.toRadians(-90.0))
                .transform(new org.joml.Vector4f(0f, 100f, 0f, 0f));
        values.put("upPosition", new float[]{up.x, up.y, up.z});
    }

    private static float[] celestialPosition(Matrix4f modelView, float sunPathRotation, float degrees) {
        org.joml.Vector4f p = new Matrix4f(modelView)
                .rotateY((float) Math.toRadians(-90.0))
                .rotateZ((float) Math.toRadians(sunPathRotation))
                .rotateX((float) Math.toRadians(degrees))
                .transform(new org.joml.Vector4f(0f, 100f, 0f, 1f));
        return new float[]{p.x, p.y, p.z};
    }

    public void capture(Matrix4fc viewRotation, Matrix4fc projection, boolean zZeroToOne) {
        Matrix4f modelView = new Matrix4f(viewRotation);
        Matrix4f proj = undoRevZ(new Matrix4f(projection), zZeroToOne);
        values.put("gbufferModelView", modelView);
        values.put("gbufferModelViewInverse", new Matrix4f(modelView).invert());
        values.put("gbufferPreviousModelView", previousModelView);
        values.put("gbufferProjection", proj);
        values.put("gbufferProjectionInverse", new Matrix4f(proj).invert());
        values.put("gbufferPreviousProjection", previousProjection);
        previousModelView = modelView;
        previousProjection = proj;
    }

    /**
     * Reversed-Z back to OpenGL's depth convention, Iris's MatrixUtils.undoRevZ: with depth 0..1
     * (Metal, and Metallum says so), z' = w - 2z, so depth 1 - d maps to OpenGL's -1..1 range.
     */
    public static Matrix4f undoRevZ(Matrix4f m, boolean zZeroToOne) {
        if (zZeroToOne) {
            m.m02(m.m03() - 2.0f * m.m02());
            m.m12(m.m13() - 2.0f * m.m12());
            m.m22(m.m23() - 2.0f * m.m22());
            m.m32(m.m33() - 2.0f * m.m32());
        } else {
            m.m02(-m.m02());
            m.m12(-m.m12());
            m.m22(-m.m22());
            m.m32(-m.m32());
        }
        return m;
    }
}
