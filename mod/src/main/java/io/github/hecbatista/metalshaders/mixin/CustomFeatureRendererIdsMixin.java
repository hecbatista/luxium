package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.hecbatista.metalshaders.render.DrawIds;
import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While custom-geometry submits are built into the draw list (CustomFeatureRenderer.buildGroup,
 * 26.3), each one's own ids apply: the loop asks each submit for its render type right before
 * getting the vertex builder for it.
 */
@Mixin(CustomFeatureRenderer.class)
abstract class CustomFeatureRendererIdsMixin {
    @WrapOperation(method = "buildGroup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/CustomFeatureRenderer$Submit;renderType()Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType luxium$build(final CustomFeatureRenderer.Submit submit, final Operation<RenderType> original) {
        DrawIds.buildSubmit(((ItemDrawState.Captured) (Object) submit).luxium$ids());
        return original.call(submit);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void luxium$built(CallbackInfo ci) {
        DrawIds.endSubmit();
    }
}
