package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.hecbatista.metalshaders.render.DrawIds;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Objects;

/**
 * When a draw is built for a RenderType (RenderTypeFeatureRenderer.Group.getOrAddDraw, 26.3), the
 * PreparedRenderType it's drawn with inherits the ids of the thing being built. Geometry with other
 * ids never joins that draw: the group's "same render type as last time" shortcut
 * (getVertexBuilder) and its search for an existing draw (getOrAddDraw's indexOf) both also ask
 * for equal ids, so things sharing one render type (the bell and the enchanting table's book, on
 * the blocks atlas) get draws of their own.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
abstract class RenderTypeFeatureRendererGroupMixin {
    @Shadow
    private StagedVertexBuffer.Draw lastDraw;

    @Unique
    private DrawIds.Ids luxium$lastIds;

    @Inject(method = "getVertexBuilder", at = @At("HEAD"))
    private void luxium$splitByIds(final RenderType renderType, final CallbackInfoReturnable<?> cir) {
        DrawIds.Ids ids = DrawIds.buildingIds(renderType);
        if (!Objects.equals(ids, luxium$lastIds)) {
            lastDraw = null;   // the game then looks the draw up again (getOrAddDraw)
        }
        luxium$lastIds = ids;
    }

    @WrapOperation(method = "getOrAddDraw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/rendertype/RenderType;prepare()Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;"))
    private PreparedRenderType luxium$prepared(final RenderType type, final Operation<PreparedRenderType> original) {
        PreparedRenderType prepared = original.call(type);
        DrawIds.notePrepared(type, prepared);
        return prepared;
    }

    @WrapOperation(method = "getOrAddDraw", at = @At(value = "INVOKE", target = "Ljava/util/List;indexOf(Ljava/lang/Object;)I"))
    private int luxium$sameIds(final List<PreparedRenderType> drawRenderTypes, final Object prepared, final Operation<Integer> original,
                               @Local(argsOnly = true) final RenderType renderType) {
        DrawIds.Ids ids = DrawIds.buildingIds(renderType);
        for (int i = 0; i < drawRenderTypes.size(); i++) {
            PreparedRenderType existing = drawRenderTypes.get(i);
            if (existing.equals(prepared) && Objects.equals(DrawIds.of(existing), ids)) {
                return i;
            }
        }
        return -1;
    }
}
