package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.render.TestPass;
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

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
    private void luxium$afterWorld(final CallbackInfo ci) {
        TestPass.draw(this.mainRenderTarget());
    }
}
