package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.hecbatista.metalshaders.render.EntityTakeover;
import io.github.hecbatista.metalshaders.render.RoutingPass;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particles (QuadParticleFeatureRenderer, 26.3) draw a group of layers through one pass of their
 * own executeGroup, not through RenderTypeFeatureRenderer. While a pack window is open the group is
 * handed a RoutingPass, and each layer's pipeline is swapped for the pack's particle program
 * where it's served; the RoutingPass then sends that layer to the pack's pass.
 */
@Mixin(QuadParticleFeatureRenderer.class)
abstract class QuadParticleFeatureRendererMixin {
    @ModifyVariable(method = "executeGroup", at = @At("HEAD"), argsOnly = true)
    private RenderPass luxium$route(final RenderPass pass) {
        RoutingPass routing = EntityTakeover.routeParticles(pass);
        return routing != null ? routing : pass;
    }

    @Inject(method = "executeGroup", at = @At("RETURN"))
    private void luxium$routed(final CallbackInfo ci, @Local(argsOnly = true) final RenderPass pass) {
        if (pass instanceof RoutingPass routing) {
            routing.finish();
            EntityTakeover.endParticles();
        }
    }

    @WrapOperation(method = "drawLayers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private static CompiledRenderPipeline luxium$particlePipeline(final RenderPipeline pipeline, final Operation<CompiledRenderPipeline> original) {
        return EntityTakeover.particlePipelineFor(pipeline, original.call(pipeline));
    }
}
