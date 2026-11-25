package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.render.PackChain;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * End portals and End gateways while a pack runs, as Iris draws them (MixinTheEndPortalRenderer
 * and MixinTheEndGatewayRenderer; see NOTICE): instead of the game's end_portal/end_gateway
 * pipelines (position-only vertices, a shader no pack program replaces), each face is an
 * entity_solid quad with the End portal texture, a fixed dark teal colour, full light and UVs that
 * scroll over 100 seconds. The pack then draws it with gbuffers_block like any block entity, and
 * reads it by blockEntityId. Without this the game's draw lands in a buffer packs like Bliss never
 * read, so the portal and gateway were invisible (they still cast shadows).
 * <p>
 * Both renderers submit through the private submitCube; outline (glowing) draws stay the game's.
 */
@Mixin(AbstractEndPortalRenderer.class)
abstract class EndPortalRendererMixin {
    private static final float RED = 0.075f;
    private static final float GREEN = 0.15f;
    private static final float BLUE = 0.2f;

    @Shadow
    @Final
    private static Map<Direction, List<Vector3fc>> FACES;

    @WrapOperation(method = "submitCube(Ljava/util/Collection;Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
    private static void luxium$packPortal(SubmitNodeCollector collector, PoseStack poseStack, RenderType renderType,
                                          SubmitNodeCollector.CustomGeometryRenderer geometry, Operation<Void> original,
                                          @Local(argsOnly = true) Collection<Direction> facesToShow) {
        if (!PackChain.ready() || renderType.isOutline()) {
            original.call(collector, poseStack, renderType, geometry);
            return;
        }
        original.call(collector, poseStack, RenderTypes.entitySolid(AbstractEndPortalRenderer.END_PORTAL_LOCATION),
                (SubmitNodeCollector.CustomGeometryRenderer) (pose, buffer) -> luxium$cube(facesToShow, pose, buffer));
    }

    private static void luxium$cube(Collection<Direction> facesToShow, PoseStack.Pose pose, VertexConsumer buffer) {
        // A period of 100 seconds; the texture repeats, so the UVs may run past 1.
        float progress = MetalShadersClient.value("frameTimeCounter") instanceof Float t ? (t * 0.01f) % 1f : 0f;
        float[][] uvs = {{0f, 0f}, {0f, 0.2f}, {0.2f, 0.2f}, {0.2f, 0f}};
        for (Direction direction : facesToShow) {
            List<Vector3fc> vertices = FACES.get(direction);
            for (int i = 0; i < 4; i++) {
                Vector3fc v = vertices.get(i);
                buffer.addVertex(pose, v.x(), v.y(), v.z()).setColor(RED, GREEN, BLUE, 1.0f)
                        .setUv(uvs[i][0] + progress, uvs[i][1] + progress).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightCoordsUtil.FULL_BRIGHT)
                        .setNormal(pose, direction.getStepX(), direction.getStepY(), direction.getStepZ());
            }
        }
    }
}
