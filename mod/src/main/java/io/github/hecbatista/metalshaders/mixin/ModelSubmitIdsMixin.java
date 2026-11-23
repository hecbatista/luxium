package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.render.DrawIds;
import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A model or custom-geometry submit (ModelFeatureRenderer.Submit, CustomFeatureRenderer.Submit,
 * 26.3) keeps the ids current when it was made, as item submits do (ItemFeatureRendererSubmitMixin;
 * Iris ModelStorage.iris$capture). See DrawIds: render types are shared, submits are not.
 */
@Mixin({ModelFeatureRenderer.Submit.class, CustomFeatureRenderer.Submit.class})
abstract class ModelSubmitIdsMixin implements ItemDrawState.Captured {
    @Unique
    private DrawIds.Ids luxium$ids;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void luxium$capture(CallbackInfo ci) {
        luxium$ids = DrawIds.current();
    }

    @Override
    public DrawIds.Ids luxium$ids() {
        return luxium$ids;
    }
}
