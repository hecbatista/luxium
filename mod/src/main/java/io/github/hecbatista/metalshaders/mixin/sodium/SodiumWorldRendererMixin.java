package io.github.hecbatista.metalshaders.mixin.sodium;

import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 0.9.2 builds a new section manager (renderer, mesh builder, regions, arena) only in
 * SodiumWorldRenderer.initRenderer (decompiled): at level load, and on the frame after
 * LevelExtractor.allChanged (which only marks the geometry stale; the next LevelExtractor.extract
 * calls LevelRenderer.invalidateCompiledGeometry, which Sodium replaces with reload). Luxium's
 * chunk format switch is applied right here, so the old section manager never sees the new
 * format and the new one sees nothing else.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
abstract class SodiumWorldRendererMixin {
    @Inject(method = "initRenderer", at = @At("HEAD"))
    private void luxium$applyMeshFormat(CallbackInfo ci) {
        MetalShadersClient.applyMeshFormat();
    }
}
