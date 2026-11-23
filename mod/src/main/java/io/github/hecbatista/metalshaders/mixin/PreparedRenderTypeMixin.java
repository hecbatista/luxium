package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.hecbatista.metalshaders.render.EntityTakeover;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Inside a served entity draw (PreparedRenderType.draw, 26.3): the pack's pipeline instead of the
 * vanilla one, and the pack's uniform block and textures bound right after it. The game's draw
 * then binds DynamicTransforms and Sampler0-2 by name, as it always does.
 */
@Mixin(PreparedRenderType.class)
abstract class PreparedRenderTypeMixin {
    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private CompiledRenderPipeline luxium$entityPipeline(final RenderPipeline pipeline, final Operation<CompiledRenderPipeline> original) {
        return EntityTakeover.pipelineFor(pipeline, original.call(pipeline));
    }

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
    private void luxium$entityBind(final RenderPass pass, final CompiledRenderPipeline compiled, final Operation<Void> original) {
        original.call(pass, compiled);
        EntityTakeover.bind(pass, compiled);
    }
}
