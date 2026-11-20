package io.github.hecbatista.metalshaders.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.render.TerrainTakeover;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inside Sodium 0.9.2's DefaultChunkRenderer.render (decompiled): while the pack's terrain pass is
 * open, the pipeline Sodium sets is swapped for the pack's program for that terrain pass, and the
 * pack's uniform block and textures are bound right after. Everything else (regions, draw
 * commands, index and vertex buffers, push constants, u_Globals) stays Sodium's.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class DefaultChunkRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void luxium$terrainPass(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
                                    CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, RenderPass pass,
                                    GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo,
                                    @Nullable OitStage stage, CallbackInfo ci) {
        TerrainTakeover.enterTerrainPass(stage == null ? renderPass : null);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private CompiledRenderPipeline luxium$packPipeline(RenderPipeline pipeline, Operation<CompiledRenderPipeline> original) {
        return TerrainTakeover.pipelineFor(original.call(pipeline));
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
    private void luxium$bindPack(RenderPass pass, CompiledRenderPipeline compiled, Operation<Void> original) {
        original.call(pass, compiled);
        TerrainTakeover.bind(pass, MetalShadersClient::value);
    }
}
