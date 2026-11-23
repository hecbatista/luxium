package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.uniform.GameUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The game's fog colour each frame (FogRenderer.setupFog, 26.3), as Iris's MixinFogRenderer captures it. */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void luxium$fogColor(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darken, ClientLevel level,
                                 CallbackInfoReturnable<FogData> cir) {
        FogData fog = cir.getReturnValue();
        GameUniforms.setFogColor(fog.color.x, fog.color.y, fog.color.z);
    }
}
