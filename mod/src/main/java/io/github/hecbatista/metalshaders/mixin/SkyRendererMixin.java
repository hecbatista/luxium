package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.render.PackChain;
import io.github.hecbatista.metalshaders.render.SkyTakeover;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Hands the overworld sky to the pack's sky programs (step 6d-1). Each hook wraps one vanilla
 * call inside SkyRenderer (26.3 sources): the pass it opens (SkyRenderer.java:139) is opened on
 * the pack's colortex0 instead of the game's colour target; each piece's pipeline is swapped for
 * the pack's, compiled for that pipeline's vertex format; the piece's model-view and colour
 * (DynamicGpuData.writeTransform) and its debug label (which says which piece it is) are noted
 * so the pack's uniform block gets them; the sun/moon texture is bound under the pack's name.
 */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void luxium$skyStart(final GpuBufferSlice skyFog, final SkyRenderState state, final CallbackInfo ci) {
        SkyTakeover.setSkybox(state.skybox);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void luxium$skyEnd(final GpuBufferSlice skyFog, final SkyRenderState state, final CallbackInfo ci) {
        SkyTakeover.leavePass();
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
    private RenderPass luxium$openSkyPass(final CommandEncoder encoder, final Supplier<String> label, final GpuTextureView color,
                                          final Optional<Vector4fc> clear, final GpuTextureView depth, final OptionalDouble clearDepth,
                                          final Operation<RenderPass> original) {
        SkyTakeover.enterPass();
        GpuTextureView colortex0 = SkyTakeover.inPass() ? PackChain.gbufferTarget(0) : null;
        if (colortex0 == null) {
            SkyTakeover.leavePass();
            return original.call(encoder, label, color, clear, depth, clearDepth);
        }
        PackChain.skyDrawn(encoder);
        return original.call(encoder, (Supplier<String>) () -> "Luxium sky (pack)", colortex0, Optional.<Vector4fc>empty(), depth, OptionalDouble.empty());
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private CompiledRenderPipeline luxium$skyPipeline(final RenderPipeline pipeline, final Operation<CompiledRenderPipeline> original) {
        return SkyTakeover.pipelineFor(pipeline, original.call(pipeline));
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
    private void luxium$skySetPipeline(final RenderPass pass, final CompiledRenderPipeline compiled, final Operation<Void> original) {
        original.call(pass, compiled);
        SkyTakeover.bindFor(pass, compiled, MetalShadersClient::value);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice luxium$skyTransform(final DynamicGpuData data, final Matrix4f modelView, final Vector4f color,
                                               final Operation<GpuBufferSlice> original) {
        SkyTakeover.noteTransform(modelView, color);
        return original.call(data, modelView, color);
    }

    /** The End sky's transform has no colour (DynamicGpuData.writeTransform(Matrix4f): white). */
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice luxium$skyTransformWhite(final DynamicGpuData data, final Matrix4f modelView,
                                                    final Operation<GpuBufferSlice> original) {
        SkyTakeover.noteTransform(modelView, new Vector4f(1, 1, 1, 1));
        return original.call(data, modelView);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;pushDebugGroup(Ljava/util/function/Supplier;)V"))
    private void luxium$skyLabel(final RenderPass pass, final Supplier<String> label, final Operation<Void> original) {
        SkyTakeover.noteLabel(label.get());
        original.call(pass, label);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V"))
    private void luxium$skyTexture(final RenderPass pass, final String name, final GpuTextureView view, final GpuSampler sampler,
                                   final Operation<Void> original) {
        original.call(pass, name, view, sampler);
        SkyTakeover.bindAlbedo(pass, name, view, sampler);
    }
}
