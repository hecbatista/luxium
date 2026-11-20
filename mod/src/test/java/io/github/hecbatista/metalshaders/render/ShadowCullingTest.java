package io.github.hecbatista.metalshaders.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The light's box test (positions relative to the camera, into the light's clip space). */
class ShadowCullingTest {
    /** A 20-block cube around the camera, seen by a light straight overhead. */
    private static ShadowCulling.LightBox overhead() {
        Matrix4f modelView = new Matrix4f().rotateX((float) Math.toRadians(90));   // look straight down
        Matrix4f projection = new Matrix4f().ortho(-10, 10, -10, 10, -10, 10);
        return new ShadowCulling.LightBox(projection.mul(modelView));
    }

    @Test
    void insideCrossingOutside() {
        ShadowCulling.LightBox box = overhead();
        assertEquals(-2, box.intersectAab(-1, -1, -1, 1, 1, 1));
        assertEquals(-1, box.intersectAab(5, -1, -1, 15, 1, 1));
        assertEquals(0, box.intersectAab(20, -1, -1, 30, 1, 1));
    }

    @Test
    void sectionsBehindTheCameraStillCount() {
        // The camera's own frustum would drop this; the light's box keeps it.
        ShadowCulling.LightBox box = overhead();
        assertTrue(box.testSection(0, 0, -8));
        assertFalse(box.testSection(0, 0, -30));
    }

    @Test
    void sectionTestPadsLikeSodium() {
        // A section whose centre is 18 blocks out still reaches the box with Sodium's 9.125 padding.
        ShadowCulling.LightBox box = overhead();
        assertTrue(box.testSection(18, 0, 0));
        assertFalse(box.testSection(20, 0, 0));
        assertTrue(box.testSectionExpanded(20, 0, 0, 1));
    }
}
