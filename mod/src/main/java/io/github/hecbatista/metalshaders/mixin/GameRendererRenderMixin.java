package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import io.github.hecbatista.metalshaders.render.PackChain;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The seam after the world is drawn: right after {@code this.renderLevel()} in
 * {@code GameRenderer.render()} (GameRenderer.java:498 in the 26.3 sources). No render pass is
 * open there, and the world's depth is still intact (it's cleared a few lines later), which is
 * where Vitrail's notes say a full-screen pass belongs (docs/internals/game-graphics-api.md).
 */
@Mixin(GameRenderer.class)
abstract class GameRendererRenderMixin {
    @Shadow
    public abstract com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget();

    /** Start of the world's frame: clear the pack's buffers before anything draws into them. */
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void luxium$beforeWorld(final CallbackInfo ci) {
        PackChain.beginFrame(this.mainRenderTarget());
    }

    /**
     * Right after the level renderer returns, before render3dHud: that method clears the depth
     * (GameRenderer.java:689) and draws the hand. Running here keeps the world's depth and lets
     * the game draw the hand over the pack's finished image.
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void luxium$afterWorld(final CallbackInfo ci) {
        PackChain.afterLevel(this.mainRenderTarget(), MetalShadersClient::value);
        if (!io.github.hecbatista.metalshaders.render.EntityTakeover.handActive()) {
            // The game draws the hand itself: the composites must run first, as before 6d-4,
            // or final would paint over it.
            PackChain.afterHand(this.mainRenderTarget(), null, MetalShadersClient::value);
        }
    }

    /**
     * After the game drew the hand (render3dHud -> renderItemInHand, GameRenderer.java:691), and
     * before the screen effects and the crosshair: the hand's depth joins depthtex0/1/2 and the
     * pack's composites and final run, so the pack's hand is in the picture they read.
     */
    @Inject(method = "render3dHud", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
            shift = At.Shift.AFTER))
    private void luxium$afterHand(final CallbackInfo ci, @Local final com.mojang.renderpearl.api.textures.GpuTexture depthTexture) {
        PackChain.afterHand(this.mainRenderTarget(), depthTexture, MetalShadersClient::value);
    }

    /** If render3dHud didn't run this frame, the composites still do (they always end the level). */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void luxium$levelDone(final CallbackInfo ci) {
        PackChain.afterHand(this.mainRenderTarget(), null, MetalShadersClient::value);
    }

    // ---- View bobbing in the model-view matrix, not the projection (as Iris, see NOTICE) ----
    //
    // Vanilla folds view bobbing, hurt shake and the nausea/portal spin into the projection
    // (renderLevel: projectionMatrix.mul(bobStack), then rotate/scale). Packs expect
    // gbufferProjection to be a plain perspective: Complementary takes view directions from it
    // and the sun's direction from gbufferModelView, so with bobbing in the projection the two
    // disagreed every step. Sprinting made the sun glare flip on sand and the reflected-sky band
    // jump on water (the user's report, 2026-11-23). Iris MixinModelViewBobbing (OptiFine does the
    // same): keep the projection plain and put the bob into the camera's view rotation, so
    // projection x view is unchanged and vanilla-drawn geometry lands where it did.

    @Unique
    private boolean luxium$moveBob;
    @Unique
    private Matrix4f luxium$bob;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void luxium$bobStart(final CallbackInfo ci) {
        luxium$moveBob = PackChain.ready();
        luxium$bob = null;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;"))
    private Matrix4f luxium$bobOutOfProjection(final Matrix4f projection, final Matrix4fc bob, final Operation<Matrix4f> original) {
        if (!luxium$moveBob) {
            return original.call(projection, bob);
        }
        luxium$bob = new Matrix4f(bob);
        return projection;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4f;rotate(FLorg/joml/Vector3fc;)Lorg/joml/Matrix4f;"))
    private Matrix4f luxium$spinRotate(final Matrix4f projection, final float angle, final Vector3fc axis, final Operation<Matrix4f> original) {
        if (!luxium$moveBob || luxium$bob == null) {
            return original.call(projection, angle, axis);
        }
        luxium$bob.rotate(angle, axis);
        return projection;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4f;scale(FFF)Lorg/joml/Matrix4f;"))
    private Matrix4f luxium$spinScale(final Matrix4f projection, final float x, final float y, final float z, final Operation<Matrix4f> original) {
        if (!luxium$moveBob || luxium$bob == null) {
            return original.call(projection, x, y, z);
        }
        luxium$bob.scale(x, y, z);
        return projection;
    }

    /** The world is drawn with the bob in its view rotation: bob x view (Iris: mulLocal). */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
    private void luxium$bobIntoView(final net.minecraft.client.renderer.LevelRenderer levelRenderer,
                                    final com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator, final boolean outline,
                                    final CameraRenderState cameraState, final com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,
                                    final org.joml.Vector4f fogColor, final boolean bossFog, final boolean consistentDepth,
                                    final Operation<Void> original) {
        if (luxium$moveBob && luxium$bob != null) {
            cameraState.viewRotationMatrix.mulLocal(luxium$bob);
        }
        original.call(levelRenderer, allocator, outline, cameraState, fog, fogColor, bossFog, consistentDepth);
    }

    /**
     * Captures the world's camera matrices where renderLevel hands its final projection to
     * RenderSystem (GameRenderer.java:665 in the 26.3 sources). With a pack in use the projection
     * is plain and the bob goes into the view rotation, as the world is then drawn with it.
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"))
    private void luxium$captureMatrices(final CallbackInfo ci,
                                         @Local(ordinal = 0) final Matrix4f projectionMatrix,
                                         @Local final CameraRenderState cameraState) {
        Matrix4fc view = luxium$moveBob && luxium$bob != null
                ? new Matrix4f(cameraState.viewRotationMatrix).mulLocal(luxium$bob)
                : cameraState.viewRotationMatrix;
        MetalShadersClient.captureMatrices(view, projectionMatrix);
    }
}
