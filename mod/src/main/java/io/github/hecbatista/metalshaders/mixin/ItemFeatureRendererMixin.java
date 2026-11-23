package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.render.DrawIds;
import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An item's render types are only known when its draws are built (ItemFeatureRenderer.prepareSubmit,
 * 26.3), so the ids the submit kept apply while it builds (Iris MixinItemFeatureRenderer).
 */
@Mixin(ItemFeatureRenderer.class)
abstract class ItemFeatureRendererMixin {
    @Inject(method = "prepareSubmit", at = @At("HEAD"))
    private void luxium$build(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        DrawIds.buildWith(((ItemDrawState.Captured) (Object) submit).luxium$ids());
    }

    @Inject(method = "prepareSubmit", at = @At("RETURN"))
    private void luxium$built(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        DrawIds.buildWith(null);
    }
}
