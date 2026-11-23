package io.github.hecbatista.metalshaders.render;

import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.mixin.sodium.RenderSectionManagerAccessor;
import io.github.hecbatista.metalshaders.mixin.sodium.SodiumWorldRendererAccessor;
import io.github.hecbatista.metalshaders.sodium.ShadowRegion;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

/**
 * Step 7c: the shadow map's terrain comes from what the light can see, not from what the camera
 * sees, so blocks behind the camera or hidden from it still cast shadows.
 * <p>
 * Follows Iris's shadow pass on the same Sodium (0.9.2; see NOTICE): around the shadow draw, every
 * region's render list and draw batches are swapped for a second set
 * (mixin.sodium.RenderRegionMixin, after Iris MixinRenderRegion), Sodium's walk that ignores the
 * camera's occlusion graph fills them (RenderSectionManager.renderOutOfGraph, as Iris's
 * finalizeShadowRenderLists does), and the batches are prepared for that list. Afterwards the
 * camera's lists, tree and task lists are put back and its batches prepared again (prepare also
 * records which passes have anything to draw, in one shared array).
 * <p>
 * The walk's frustum is the shadow map's own box: a section counts if its corners, taken into the
 * light's clip space (shadowProjection x shadowModelView, positions relative to the camera, as the
 * shadow programs use them), overlap the unit cube. Iris uses a tighter "advanced" frustum by
 * default; this box is a superset, so it never drops a caster, at the cost of drawing a few
 * sections whose shadows fall outside the camera's view.
 * <p>
 * Debug: {@code LUXIUM_SHADOW_CAMERA_LIST=1} keeps the camera's list (the old behaviour), for
 * before/after shots.
 */
public final class ShadowCulling {
    private static final boolean CAMERA_LIST = System.getenv("LUXIUM_SHADOW_CAMERA_LIST") != null;
    private static long lastLog;
    /** True while the light's batches are being filled (DefaultChunkRendererMixin keeps every face then). */
    private static boolean fillingLightBatches;

    private ShadowCulling() {
    }

    /** Runs {@code draw} with Sodium's render lists replaced by the light's. */
    public static void withLightLists(Runnable draw) {
        SodiumWorldRenderer world = SodiumWorldRenderer.instanceNullable();
        if (CAMERA_LIST || world == null || !(MetalShadersClient.value("shadowProjection") instanceof Matrix4fc projection)
                || !(MetalShadersClient.value("shadowModelView") instanceof Matrix4fc modelView)) {
            draw.run();
            return;
        }
        SodiumWorldRendererAccessor worldAccess = (SodiumWorldRendererAccessor) world;
        RenderSectionManager manager = worldAccess.luxium$renderSectionManager();
        if (manager == null) {
            draw.run();
            return;
        }
        RenderSectionManagerAccessor access = (RenderSectionManagerAccessor) manager;
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();

        SortedRenderLists cameraLists = access.luxium$renderLists();
        DeferredTaskList cameraTasks = access.luxium$taskLists();
        SectionTree cameraTree = access.luxium$renderTree();
        for (RenderRegion region : manager.regions.getLoadedRegions()) {
            ((ShadowRegion) region).luxium$swapToShadow();
        }
        try {
            Matrix4f lightClip = new Matrix4f(projection).mul(modelView);
            Viewport viewport = new Viewport(new LightBox(lightClip), new Vector3d(camera.x, camera.y, camera.z));
            access.luxium$renderOutOfGraph(viewport, worldAccess.luxium$lastFogParameters());
            fillingLightBatches = true;
            try {
                world.prepareChunkRendering(null, camera.x, camera.y, camera.z);
            } finally {
                fillingLightBatches = false;
            }
            long now = System.nanoTime();
            if (now - lastLog > 5_000_000_000L) {
                lastLog = now;
                MetalShadersClient.LOGGER.info("Shadow terrain: {} regions seen by the light, {} by the camera",
                        count(access.luxium$renderLists()), count(cameraLists));
            }
            draw.run();
        } finally {
            access.luxium$setRenderLists(cameraLists);
            access.luxium$setTaskLists(cameraTasks);
            access.luxium$setRenderTree(cameraTree);
            for (RenderRegion region : manager.regions.getLoadedRegions()) {
                ((ShadowRegion) region).luxium$swapToCamera();
            }
            world.prepareChunkRendering(null, camera.x, camera.y, camera.z);
        }
    }

    /**
     * Whether a batch being filled now must keep faces turned away from the camera: the light's
     * batches always (those faces stand between the sun and the ground), and, when the shadow
     * pass shares the camera's batches (LUXIUM_SHADOW_CAMERA_LIST), the camera's too.
     */
    public static boolean keepAllFaces() {
        return fillingLightBatches || (CAMERA_LIST && TerrainTakeover.shadowActive());
    }

    private static int count(SortedRenderLists lists) {
        int n = 0;
        for (var it = lists.iterator(false); it.hasNext(); it.next()) {
            n++;
        }
        return n;
    }

    /** The shadow map's box as a Sodium frustum (positions relative to the camera). */
    static final class LightBox implements Frustum {
        /** Sodium's section test pads a section's half size (8) by its margin (SimpleFrustum: 9.125). */
        private static final float SECTION_RADIUS = 9.125f;
        private final Matrix4f lightClip;

        LightBox(Matrix4f lightClip) {
            this.lightClip = lightClip;
        }

        /** -2 inside, -1 crossing, 0 outside (JOML FrustumIntersection, as Sodium reads it). */
        @Override
        public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < 8; i++) {
                float x = (i & 1) == 0 ? minX : maxX;
                float y = (i & 2) == 0 ? minY : maxY;
                float z = (i & 4) == 0 ? minZ : maxZ;
                // An orthographic projection: w stays 1.
                float cx = lightClip.m00() * x + lightClip.m10() * y + lightClip.m20() * z + lightClip.m30();
                float cy = lightClip.m01() * x + lightClip.m11() * y + lightClip.m21() * z + lightClip.m31();
                float cz = lightClip.m02() * x + lightClip.m12() * y + lightClip.m22() * z + lightClip.m32();
                lo[0] = Math.min(lo[0], cx);
                hi[0] = Math.max(hi[0], cx);
                lo[1] = Math.min(lo[1], cy);
                hi[1] = Math.max(hi[1], cy);
                lo[2] = Math.min(lo[2], cz);
                hi[2] = Math.max(hi[2], cz);
            }
            boolean inside = true;
            for (int a = 0; a < 3; a++) {
                if (hi[a] < -1f || lo[a] > 1f) {
                    return 0;
                }
                inside &= lo[a] >= -1f && hi[a] <= 1f;
            }
            return inside ? -2 : -1;
        }

        @Override
        public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return intersectAab(minX, minY, minZ, maxX, maxY, maxZ) != 0;
        }

        @Override
        public boolean testSection(float x, float y, float z) {
            return testAab(x - SECTION_RADIUS, y - SECTION_RADIUS, z - SECTION_RADIUS,
                    x + SECTION_RADIUS, y + SECTION_RADIUS, z + SECTION_RADIUS);
        }

        @Override
        public boolean testSectionExpanded(float x, float y, float z, float extend) {
            float r = SECTION_RADIUS + extend;
            return testAab(x - r, y - r, z - r, x + r, y + r, z + r);
        }
    }
}
