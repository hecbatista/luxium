package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.uniform.GameUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game's fog each frame (FogRenderer.setupFog, 26.3), as Iris captures it: the colour
 * (Iris MixinFogRenderer), the environmental start and end (Sodium's FogRendererMixin, which
 * Iris reads for fogStart, fogEnd and gl_Fog), and the legacy water fog density (Iris
 * MixinFogRenderer: 0.05 minus waterVision squared times 0.03 under water, else none).
 */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void luxium$fog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darken, ClientLevel level,
                            CallbackInfoReturnable<FogData> cir) {
        FogData fog = cir.getReturnValue();
        float density = -1.0f;
        if (camera.getFluidInCamera() == FogType.WATER) {
            density = 0.05f;
            if (camera.entity() instanceof LocalPlayer player) {
                density -= player.getWaterVision() * player.getWaterVision() * 0.03f;
            }
        }
        GameUniforms.setFog(fog.color.x, fog.color.y, fog.color.z, fog.color.w,
                fog.environmentalStart, fog.environmentalEnd, density);
    }
}
