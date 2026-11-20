package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.hecbatista.metalshaders.render.DrawIds;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * When a draw is built for a RenderType (RenderTypeFeatureRenderer.Group.getOrAddDraw, 26.3), the
 * PreparedRenderType it's drawn with inherits the ids of the thing that submitted that type.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
abstract class RenderTypeFeatureRendererGroupMixin {
    @WrapOperation(method = "getOrAddDraw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/rendertype/RenderType;prepare()Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;"))
    private PreparedRenderType luxium$prepared(final RenderType type, final Operation<PreparedRenderType> original) {
        PreparedRenderType prepared = original.call(type);
        DrawIds.notePrepared(type, prepared);
        return prepared;
    }
}
