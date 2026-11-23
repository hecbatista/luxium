package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.render.DrawIds;
import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An item submit keeps the ids current when it was made (Iris ModelStorage.iris$capture). */
@Mixin(ItemFeatureRenderer.Submit.class)
abstract class ItemFeatureRendererSubmitMixin implements ItemDrawState.Captured {
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
