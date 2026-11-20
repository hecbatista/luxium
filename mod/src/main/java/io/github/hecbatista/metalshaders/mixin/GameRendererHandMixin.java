package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.render.EntityTakeover;
import io.github.hecbatista.metalshaders.render.LevelPass;
import io.github.hecbatista.metalshaders.render.PackChain;
import io.github.hecbatista.metalshaders.uniform.RenderMatrices;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Matrix4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * The player's hand drawn by the pack's gbuffers_hand (step 6d-4). GameRenderer.renderItemInHand
 * (26.3, GameRenderer.java:382-420) submits the hand into its own node storage and draws it with
 * its own feature dispatcher into an "Item in hand" pass, under the hud projection, after the
 * depth was cleared. That pass is handed to the game as a LevelPass, and the dispatcher's run is
 * the hand window: served draws go to the pack's targets with the hand's depth squeeze, others to
 * the game's pass. The pack's composites run right after (GameRendererRenderMixin.luxium$afterHand).
 */
@Mixin(GameRenderer.class)
abstract class GameRendererHandMixin {
    @Shadow
    @Final
    private Projection hudProjection;

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
    private RenderPass luxium$handPass(final CommandEncoder encoder, final Supplier<String> label, final GpuTextureView color,
                                       final Optional<Vector4fc> clear, final GpuTextureView depth, final OptionalDouble clearDepth,
                                       final Operation<RenderPass> original) {
        RenderPass pass = original.call(encoder, label, color, clear, depth, clearDepth);
        return PackChain.ready() && EntityTakeover.handActive() && clear.isEmpty() && clearDepth.isEmpty()
                ? new LevelPass(pass, label, color, depth) : pass;
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void luxium$handWindow(final RenderPass pass, final FeatureRenderDispatcher.PreparedFrame frame, final Operation<Void> original,
                                   @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) final GpuTextureView depth) {
        if (!(pass instanceof LevelPass level)) {
            original.call(pass, frame);
            return;
        }
        level.suspend();
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        Matrix4f handProjection = RenderMatrices.undoRevZ(this.hudProjection.getMatrix(new Matrix4f()), zeroToOne);
        EntityTakeover.openHandWindow(MetalShadersClient::value, handProjection, depth);
        try {
            original.call(pass, frame);
        } finally {
            EntityTakeover.closeWindow();
        }
    }
}
