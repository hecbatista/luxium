package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import io.github.hecbatista.metalshaders.render.PackChain;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    /**
     * Captures the world's camera matrices where renderLevel hands its final projection (with view
     * bobbing) to RenderSystem (GameRenderer.java:665 in the 26.3 sources).
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"))
    private void luxium$captureMatrices(final CallbackInfo ci,
                                         @Local(ordinal = 0) final Matrix4f projectionMatrix,
                                         @Local final CameraRenderState cameraState) {
        MetalShadersClient.captureMatrices(cameraState.viewRotationMatrix, projectionMatrix);
    }
}
