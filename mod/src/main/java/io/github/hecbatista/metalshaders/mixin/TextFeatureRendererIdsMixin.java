package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.hecbatista.metalshaders.render.DrawIds;
import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While text submits are built into the draw list (TextFeatureRenderer.buildGroup, 26.3), each
 * one's own ids apply, as for custom geometry (CustomFeatureRendererIdsMixin): the loop reads each
 * submit's pose before building its glyphs. So a sign's text is drawn as that block entity's
 * (Iris IrisPipelines.getText: TEXT_BE, with the sign's blockEntityId), not as a shared text draw.
 */
@Mixin(TextFeatureRenderer.class)
abstract class TextFeatureRendererIdsMixin {
    @WrapOperation(method = "buildGroup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;pose()Lorg/joml/Matrix4fc;"))
    private Matrix4fc luxium$build(final TextFeatureRenderer.Submit submit, final Operation<Matrix4fc> original) {
        DrawIds.buildSubmit(((ItemDrawState.Captured) (Object) submit).luxium$ids());
        return original.call(submit);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void luxium$built(CallbackInfo ci) {
        DrawIds.endSubmit();
    }
}
