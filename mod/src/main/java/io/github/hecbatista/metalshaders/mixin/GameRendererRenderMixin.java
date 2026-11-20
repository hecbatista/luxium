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

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
    private void luxium$afterWorld(final CallbackInfo ci) {
        PackChain.draw(this.mainRenderTarget(), MetalShadersClient::value);
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
