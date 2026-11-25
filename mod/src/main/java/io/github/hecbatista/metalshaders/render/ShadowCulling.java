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
import org.joml.Vector3f;

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
 * The walk's frustum, as Iris chooses it (ShadowRenderer.createShadowFrustum; see NOTICE):
 * <ul>
 *   <li>by default, {@link ShadowFrustum}: the camera frustum swept toward the light (Iris's
 *       "advanced" frustum), and also inside the shadow map's box. Measured 2026-11-23, the box
 *       alone drew about 2.8x LeadLight's shadow geometry (docs/release/benchmarks.md).</li>
 *   <li>the shadow map's box alone ({@link LightBox}: a section counts if its corners, taken into
 *       the light's clip space, overlap the unit cube) when the pack asks for distance-only culling
 *       ({@code shadow.culling=false}, or {@code reversed}/{@code safe_zone}, which Luxium doesn't
 *       implement), or when it has a shadow geometry program, Iris's sign of voxelization (casters
 *       outside the view then matter).</li>
 * </ul>
 * <p>
 * Debug: {@code LUXIUM_SHADOW_CAMERA_LIST=1} keeps the camera's list (before 7c);
 * {@code LUXIUM_SHADOW_CULL=box}, or the system property {@code luxium.shadowCull=box} (read every
 * frame, for the tour's quick A/B), forces the box (before the tight frustum).
 */
public final class ShadowCulling {
    private static final boolean CAMERA_LIST = System.getenv("LUXIUM_SHADOW_CAMERA_LIST") != null;
    private static final boolean FORCE_BOX = "box".equals(System.getenv("LUXIUM_SHADOW_CULL"));
    /** Whether the pack allows the tight frustum (set at pack load, {@link #configure}). */
    private static volatile boolean tightAllowed = true;
    private static long lastLog;
    /** True while the light's batches are being filled (DefaultChunkRendererMixin keeps every face then). */
    private static boolean fillingLightBatches;

    private ShadowCulling() {
    }

    /**
     * Which frustum the pack allows, from its {@code shadow.culling} directive (Iris
     * ShaderProperties: false = distance only, true = advanced, reversed/safe_zone = safe zone) and
     * whether it has a shadow geometry program (Iris: assume voxelization). Returns the choice for the log.
     */
    public static String configure(String cullingDirective, boolean shadowGeometry) {
        String d = cullingDirective == null ? "" : cullingDirective.strip();
        boolean tight = switch (d) {
            case "true" -> true;
            case "false", "reversed", "safe_zone" -> false;
            default -> !shadowGeometry;
        };
        tightAllowed = tight;
        return (tight ? "tight (camera frustum swept toward the light)" : "the shadow map's box")
                + " (shadow.culling=" + (d.isEmpty() ? "not set" : d) + (shadowGeometry ? ", shadow geometry program" : "") + ")"
                + (FORCE_BOX ? "; LUXIUM_SHADOW_CULL=box forces the box" : "");
    }

    /**
     * The frustum the light's walk uses: {@link ShadowFrustum} where allowed and the camera's
     * matrices are known, else the shadow map's box.
     */
    static Frustum lightFrustum(Matrix4fc shadowProjection, Matrix4fc shadowModelView, Object cameraProjection, Object cameraModelView) {
        LightBox box = new LightBox(new Matrix4f(shadowProjection).mul(shadowModelView));
        if (FORCE_BOX || "box".equals(System.getProperty("luxium.shadowCull")) || !tightAllowed || !(cameraProjection instanceof Matrix4fc proj) || !(cameraModelView instanceof Matrix4fc view)) {
            return box;
        }
        // Toward the light in world axes: the light view's +Z (row 2 of its rotation), the way the
        // shadow camera looks from the light (Iris passes getShadowLightPositionInWorldSpace).
        Vector3f towardLight = new Vector3f(shadowModelView.m02(), shadowModelView.m12(), shadowModelView.m22());
        if (towardLight.lengthSquared() < 1e-12f) {
            return box;
        }
        towardLight.normalize();
        return new ShadowFrustum(new Matrix4f(proj).mul(view), towardLight, box);
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
            Frustum frustum = lightFrustum(projection, modelView,
                    MetalShadersClient.value("gbufferProjection"), MetalShadersClient.value("gbufferModelView"));
            Viewport viewport = new Viewport(frustum, new Vector3d(camera.x, camera.y, camera.z));
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
