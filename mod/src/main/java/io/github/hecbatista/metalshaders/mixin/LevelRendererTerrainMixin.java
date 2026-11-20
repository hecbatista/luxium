package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.hecbatista.metalshaders.render.TerrainTakeover;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Hands the opaque terrain to the pack (step 6d-2). In 26.3 the level draws its opaque terrain
 * first inside the "Main" pass it opens in addMainPass's lambda (LevelRenderer.java:449-461), then
 * the solid features into the same pass. The pack's terrain writes several targets, so it's drawn
 * in a pass of its own just before "Main" opens, and the game's own opaque-terrain call in
 * executeSolid (LevelRenderer.java:524) is skipped for that frame.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererTerrainMixin {
    @Shadow
    private GpuSampler chunkLayerSampler;

    @Shadow
    @Final
    private TextureManager textureManager;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
    private RenderPass luxium$terrainBeforeMain(final CommandEncoder encoder, final Supplier<String> label, final GpuTextureView color,
                                                final Optional<Vector4fc> clear, final GpuTextureView depth, final OptionalDouble clearDepth,
                                                final Operation<RenderPass> original,
                                                @Local(argsOnly = true) final ChunkSectionsToRender sections) {
        TerrainTakeover.noteSections(sections);
        GpuTextureView atlas = this.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        TerrainTakeover.drawOpaque(depth, this.chunkLayerSampler, atlas, this.levelRenderState.renderWireframeTerrain);
        return original.call(encoder, label, color, clear, depth, clearDepth);
    }

    @WrapOperation(method = "executeSolid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
    private void luxium$skipOpaqueTerrain(final ChunkSectionsToRender sections, final ChunkSectionLayerGroup group, final RenderPass pass,
                                          final GpuSampler sampler, final GpuTextureView atlas, final boolean wireframe,
                                          final Operation<Void> original) {
        if (group == ChunkSectionLayerGroup.OPAQUE && TerrainTakeover.drewOpaque()) {
            return;
        }
        original.call(sections, group, pass, sampler, atlas, wireframe);
    }
}
