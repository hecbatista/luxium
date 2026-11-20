package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.hecbatista.metalshaders.render.EntityTakeover;
import io.github.hecbatista.metalshaders.render.LevelPass;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes each draw of a group (RenderTypeFeatureRenderer.executeGroup, 26.3) during the level's
 * solid features: a draw the pack's entity program serves goes into the pack's pass (the level's
 * pass steps aside), any other goes to the level's pass as before (which closes the pack's first).
 */
@Mixin(RenderTypeFeatureRenderer.class)
abstract class RenderTypeFeatureRendererMixin {
    @WrapOperation(method = "executeGroup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void luxium$route(final PreparedRenderType type, final StagedVertexBuffer.ExecuteInfo info, final RenderPass pass,
                              final Operation<Void> original) {
        if (pass instanceof LevelPass level && EntityTakeover.targetsReady() && EntityTakeover.serves(type.pipeline())) {
            level.suspend();
            RenderPass hold = EntityTakeover.hold();
            EntityTakeover.enterDraw();
            try {
                original.call(type, info, hold);
            } finally {
                EntityTakeover.leaveDraw();
            }
            return;
        }
        original.call(type, info, pass);
    }
}
