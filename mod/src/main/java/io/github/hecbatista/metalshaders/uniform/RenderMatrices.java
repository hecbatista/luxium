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
 * gbufferModelView is the camera's rotation only (positions are relative to the camera);
 * gbufferProjection is the game's projection, view bobbing included, turned from reversed-Z back
 * into the OpenGL convention; each also comes inverted and as last frame's value.
 */
public final class RenderMatrices {
    private final Map<String, Object> values = new HashMap<>();
    private Matrix4f previousModelView = new Matrix4f();
    private Matrix4f previousProjection = new Matrix4f();

    public Object get(String name) {
        return values.get(name);
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
