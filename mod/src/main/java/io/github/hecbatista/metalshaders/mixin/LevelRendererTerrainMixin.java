package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.render.EntityTakeover;
import io.github.hecbatista.metalshaders.render.LevelPass;
import io.github.hecbatista.metalshaders.render.PackChain;
import io.github.hecbatista.metalshaders.render.TerrainTakeover;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
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
        RenderPass main = original.call(encoder, label, color, clear, depth, clearDepth);
        // The pack draws the opaque world: hand the level a pass that can step aside for the
        // pack's entity passes (LevelPass). Clearing never happens on "Main", so reopening is safe.
        return TerrainTakeover.drewOpaque() && clear.isEmpty() && clearDepth.isEmpty()
                ? new LevelPass(main, label, color, depth) : main;
    }

    /** The level's solid features (entities, block entities): the pack's entity window. */
    @WrapOperation(method = "executeSolid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void luxium$solidFeatures(final FeatureRenderDispatcher.PreparedFrame frame, final RenderPass pass, final Operation<Void> original) {
        if (!(pass instanceof LevelPass level)) {
            original.call(frame, pass);
            return;
        }
        level.suspend();   // the window resolves textures and writes a buffer: no pass may be open
        EntityTakeover.openWindow(MetalShadersClient::value);
        try {
            original.call(frame, pass);
        } finally {
            EntityTakeover.closeWindow();
        }
    }

    /**
     * The chain's "after opaque" cut (Vitrail frame.md), right where the level starts its
     * translucent phase (LevelRenderer.java:461, executeClassicTransparency, inside "Main"):
     * "Main" is closed, the seed and deferred passes run, the pack's water is drawn, and the rest
     * of the game's translucent phase (features, clouds, weather) is drawn into a pass on the
     * translucent catch (same depth) which is then composed onto colortex0.
     */
    @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void luxium$afterOpaque(final LevelRenderer self, final ChunkSectionsToRender sections,
                                    final FeatureRenderDispatcher.PreparedFrame featureFrame, final RenderPass mainPass,
                                    final Operation<Void> original) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (!PackChain.ready() || !TerrainTakeover.drewOpaque()) {
            original.call(self, sections, featureFrame, mainPass);
            return;
        }
        mainPass.close();   // closing is idempotent: the level's own close at the end of its block does nothing
        PackChain.drawDeferred(main, MetalShadersClient::value);
        GpuTextureView atlas = this.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        TerrainTakeover.drawTranslucent(main.getDepthTextureView(), this.chunkLayerSampler, atlas, this.levelRenderState.renderWireframeTerrain);
        GpuTextureView caught = PackChain.catchTarget(main);
        GpuTextureView color = caught != null ? caught : main.getColorTextureView();
        java.util.function.Supplier<String> label = () -> caught != null ? "Luxium translucent catch" : "Main (after Luxium deferred)";
        RenderPass real = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                label, color, Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty());
        RenderSystem.bindDefaultUniforms(real);
        // A stand-in again, so the pack's translucent entities can step into the middle of it.
        LevelPass pass = new LevelPass(real, label, color, main.getDepthTextureView());
        try {
            original.call(self, sections, featureFrame, pass);
        } finally {
            pass.close();
        }
        if (caught != null) {
            PackChain.composeCatch();
        }
    }

    /** The pack's clouds=off: the game's clouds aren't drawn (the pack draws its own in its passes). */
    @WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/CloudRenderer;render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void luxium$clouds(final net.minecraft.client.renderer.CloudRenderer clouds, final net.minecraft.client.CloudStatus status,
                               final RenderPass pass, final Operation<Void> original) {
        if (!PackChain.cloudsOff()) {
            original.call(clouds, status, pass);
        }
    }

    /** The level's translucent features (after the deferred cut): the pack's translucent entity window. */
    @WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void luxium$translucentFeatures(final FeatureRenderDispatcher.PreparedFrame frame, final RenderPass pass, final Operation<Void> original) {
        if (!(pass instanceof LevelPass level)) {
            original.call(frame, pass);
            return;
        }
        level.suspend();
        EntityTakeover.openTranslucentWindow(MetalShadersClient::value);
        try {
            original.call(frame, pass);
        } finally {
            EntityTakeover.closeWindow();
        }
    }

    /** Rain and snow (WeatherEffectRenderer.render): routed to the pack's weather program. */
    @WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void luxium$weather(final net.minecraft.client.renderer.WeatherEffectRenderer weather,
                                final net.minecraft.client.renderer.state.level.WeatherRenderState state, final RenderPass pass,
                                final Operation<Void> original) {
        io.github.hecbatista.metalshaders.render.RoutingPass routing = EntityTakeover.routeWeather(pass, MetalShadersClient::value);
        if (routing == null) {
            original.call(weather, state, pass);
            return;
        }
        try {
            original.call(weather, state, routing);
        } finally {
            routing.finish();
            EntityTakeover.endParticles();
            EntityTakeover.closeWindow();
        }
    }

    /** The level's "after terrain" features (translucent particles): the pack's translucent window again. */
    @WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucentAfterTerrain(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void luxium$afterTerrainFeatures(final FeatureRenderDispatcher.PreparedFrame frame, final RenderPass pass, final Operation<Void> original) {
        if (!(pass instanceof LevelPass level)) {
            original.call(frame, pass);
            return;
        }
        level.suspend();
        EntityTakeover.openTranslucentWindow(MetalShadersClient::value);
        try {
            original.call(frame, pass);
        } finally {
            EntityTakeover.closeWindow();
        }
    }

    @WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
    private void luxium$skipTranslucentTerrain(final ChunkSectionsToRender sections, final ChunkSectionLayerGroup group, final RenderPass pass,
                                               final GpuSampler sampler, final GpuTextureView atlas, final boolean wireframe,
                                               final Operation<Void> original) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT && TerrainTakeover.drewTranslucent()) {
            return;
        }
        original.call(sections, group, pass, sampler, atlas, wireframe);
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
