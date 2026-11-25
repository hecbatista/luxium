package io.github.hecbatista.metalshaders.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tight shadow frustum: the camera frustum swept toward the light (positions relative to the camera). */
class ShadowFrustumTest {
    /** A camera looking along -Z (the identity view), 70° vertical FOV, 16:9, near 0.05, far 256. */
    private static Matrix4f cameraClip() {
        return new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 256f);
    }

    /** A box big enough to never matter, so these tests see the swept frustum alone. */
    private static ShadowCulling.LightBox hugeBox() {
        return new ShadowCulling.LightBox(new Matrix4f().ortho(-10000, 10000, -10000, 10000, -10000, 10000));
    }

    private static ShadowFrustum sunOverhead() {
        return new ShadowFrustum(cameraClip(), new Vector3f(0, 1, 0), hugeBox());
    }

    @Test
    void basePlanesFaceInward() {
        // A point straight ahead, inside the view, is on the inner side of all six planes.
        for (Vector4f p : ShadowFrustum.basePlanes(cameraClip())) {
            assertTrue(p.x * 0 + p.y * 0 + p.z * -10 + p.w > 0, "plane " + p);
        }
    }

    @Test
    void keepsWhatTheCameraSees() {
        assertTrue(sunOverhead().testSection(0, 0, -40));
    }

    @Test
    void keepsCastersAboveTheView() {
        // Overhead sun: a section high above the visible ground shades it, though the camera can't see it.
        assertTrue(sunOverhead().testSection(0, 120, -40));
    }

    @Test
    void dropsSectionsBehindTheCamera() {
        // Behind the camera, at the camera's height: its shadow falls straight down, behind the view.
        assertFalse(sunOverhead().testSection(0, 0, 60));
    }

    @Test
    void dropsSectionsFarToTheSide() {
        assertFalse(sunOverhead().testSection(200, 0, -40));
    }

    @Test
    void lowSunKeepsCastersTowardTheSun() {
        // Sun low in the east (+X): a section east of the view, outside it, can throw a long shadow in.
        ShadowFrustum f = new ShadowFrustum(cameraClip(), new Vector3f(1, 0.1f, 0).normalize(), hugeBox());
        assertTrue(f.testSection(120, 0, -40));
        // The same distance west, away from the sun, can't.
        assertFalse(f.testSection(-200, 0, -40));
    }

    @Test
    void theShadowMapBoxStillApplies() {
        // A 20-block box overhead (as ShadowCullingTest): a section in view but outside the box is dropped.
        Matrix4f light = new Matrix4f().ortho(-10, 10, -10, 10, -10, 10).mul(new Matrix4f().rotateX((float) Math.toRadians(90)));
        ShadowFrustum f = new ShadowFrustum(cameraClip(), new Vector3f(0, 1, 0), new ShadowCulling.LightBox(light));
        assertTrue(f.testSection(0, 0, -8));
        assertFalse(f.testSection(0, 0, -60));
    }

    @Test
    void intersectCodes() {
        ShadowFrustum f = sunOverhead();
        assertEquals(-2, f.intersectAab(-1, -1, -21, 1, 1, -19));   // well inside
        assertEquals(0, f.intersectAab(-1, -1, 59, 1, 1, 61));      // behind
    }

    @Test
    void configureFollowsIris() {
        assertTrue(ShadowCulling.configure(null, false).startsWith("tight"));
        assertTrue(ShadowCulling.configure(null, true).startsWith("the shadow map's box"));
        assertTrue(ShadowCulling.configure("true", true).startsWith("tight"));
        assertTrue(ShadowCulling.configure("false", false).startsWith("the shadow map's box"));
        assertTrue(ShadowCulling.configure("reversed", false).startsWith("the shadow map's box"));
        ShadowCulling.configure(null, false);
    }
}
