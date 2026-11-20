package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs our per-frame update right after Minecraft copies the game's state for drawing
 * ({@code GameRenderer.extract}, GameRenderer.java:443 in the 26.3 sources). The camera has just
 * been placed for this frame (extractCamera), so camera values are current.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "extract", at = @At("TAIL"))
    private void luxium$afterExtract(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
        MetalShadersClient.onFrameExtracted(deltaTracker);
    }
}
