package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Step 7d: the game's "Improved Transparency" (its order-independent transparency path,
 * LevelRenderer.executeOit) stays off while a pack is in use, because the pack does its own
 * transparency. Same hook points and rule as Iris's MixinDisableFabulousGraphics (see NOTICE):
 * the start of LevelExtractor.allChanged, which runs whenever the option is changed (in the
 * menu or with the debug key), and of onResourceManagerReload.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void luxium$noOitOnReload(CallbackInfo ci) {
        MetalShadersClient.keepImprovedTransparencyOff();
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void luxium$noOitOnResourceReload(CallbackInfo ci) {
        MetalShadersClient.keepImprovedTransparencyOff();
    }
}
