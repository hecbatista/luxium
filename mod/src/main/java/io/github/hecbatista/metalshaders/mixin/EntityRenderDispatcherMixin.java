package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.hecbatista.metalshaders.render.DrawIds;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** While an entity is submitted (EntityRenderDispatcher.submit, 26.3), its pack entityId is current (DrawIds). */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private void luxium$enter(final CallbackInfo ci, @Local(argsOnly = true) final EntityRenderState state) {
        DrawIds.enterEntity(state);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void luxium$leave(final CallbackInfo ci) {
        DrawIds.leave();
    }

    /**
     * No vanilla blob shadows under entities while the pack has its own shadow map, as Iris
     * (MixinEntityRenderDispatcher, IrisRenderingPipeline.shouldDisableVanillaEntityShadows: "OptiFine
     * seems to disable vanilla shadows when the shaderpack uses shadow mapping"; see NOTICE).
     * Luxium drew them, and they flashed (the user's report, 2026-11-23).
     */
    @WrapWithCondition(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V"))
    private boolean luxium$keepVanillaShadow(final SubmitNodeCollector collector, final PoseStack poseStack, final float radius,
                                             final List<EntityRenderState.ShadowPiece> pieces) {
        return !io.github.hecbatista.metalshaders.render.TerrainTakeover.shadowActive();
    }
}
