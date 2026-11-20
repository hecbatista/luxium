package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.hecbatista.metalshaders.render.DrawIds;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes which RenderType the entity or block entity being submitted draws with (SubmitNodeCollection, 26.3). */
@Mixin(SubmitNodeCollection.class)
abstract class SubmitNodeCollectionMixin {
    @Inject(method = "submitModel", at = @At("HEAD"))
    private void luxium$model(final CallbackInfo ci, @Local(argsOnly = true) final RenderType type) {
        DrawIds.noteRenderType(type);
    }

    @Inject(method = "submitCustomGeometry", at = @At("HEAD"))
    private void luxium$custom(final CallbackInfo ci, @Local(argsOnly = true) final RenderType type) {
        DrawIds.noteRenderType(type);
    }
}
