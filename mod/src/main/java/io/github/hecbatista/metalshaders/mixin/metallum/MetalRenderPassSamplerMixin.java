package io.github.hecbatista.metalshaders.mixin.metallum;

import io.github.hecbatista.metalshaders.render.CustomTextures;
import io.github.kokodio.metaljvm.metal.MTLRenderCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLSamplerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The draw-time half of {@link MetalSamplerSlotsMixin}: a texture in slot 16 or above has its
 * sampler built into the shader, so no sampler state is bound for it (Metal has no such slot).
 * The texture itself is still bound. Targets Metallum 467d312b
 * ({@code MetalRenderPass.bindTextureAndSampler}).
 */
@Mixin(targets = "com.metallum.render.MetalRenderPass", remap = false)
public abstract class MetalRenderPassSamplerMixin {
    @Redirect(method = "bindTextureAndSampler", at = @At(value = "INVOKE",
            target = "Lio/github/kokodio/metaljvm/metal/MTLRenderCommandEncoder;setVertexSamplerState(Lio/github/kokodio/metaljvm/metal/MTLSamplerState;J)V"))
    private static void luxium$vertexSampler(MTLRenderCommandEncoder encoder, MTLSamplerState sampler, long index) {
        if (index < CustomTextures.SAMPLER_SLOTS) {
            encoder.setVertexSamplerState(sampler, index);
        }
    }

    @Redirect(method = "bindTextureAndSampler", at = @At(value = "INVOKE",
            target = "Lio/github/kokodio/metaljvm/metal/MTLRenderCommandEncoder;setFragmentSamplerState(Lio/github/kokodio/metaljvm/metal/MTLSamplerState;J)V"))
    private static void luxium$fragmentSampler(MTLRenderCommandEncoder encoder, MTLSamplerState sampler, long index) {
        if (index < CustomTextures.SAMPLER_SLOTS) {
            encoder.setFragmentSamplerState(sampler, index);
        }
    }
}
