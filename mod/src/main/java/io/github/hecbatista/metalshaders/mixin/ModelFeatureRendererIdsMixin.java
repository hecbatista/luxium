package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.render.DrawIds;
import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While one model submit is built into the draw list (ModelFeatureRenderer.prepareModel, 26.3), its own ids apply. */
@Mixin(ModelFeatureRenderer.class)
abstract class ModelFeatureRendererIdsMixin {
    @Inject(method = "prepareModel", at = @At("HEAD"))
    private void luxium$build(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
        DrawIds.buildSubmit(((ItemDrawState.Captured) (Object) submit).luxium$ids());
    }

    @Inject(method = "prepareModel", at = @At("RETURN"))
    private void luxium$built(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
        DrawIds.endSubmit();
    }
}
