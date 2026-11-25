package io.github.hecbatista.metalshaders.mixin.metallum;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import io.github.hecbatista.metalshaders.render.CustomTextures;

/**
 * More than 16 textures in a program. Metallum gives each texture the sampler slot of the same
 * index ({@code MetalCrossShaderCompiler.remapResourceBindings}: msl_texture = msl_sampler), but
 * Metal has only 16 sampler slots, and a function may use at most 16 samplers, counting every
 * sampler argument and every constexpr sampler it samples with (measured with xcrun metal:
 * "uses too many samplers (17)"). Photon's deferred4 binds 17 textures but filters only about
 * 9 of them; the rest it reads with texelFetch, which needs no sampler.
 * <p>
 * So in a pipeline with more than 16 textures, every texture gets a sampler built into the shader
 * (SPIRV-Cross's constexpr sampler, no mipmaps) instead of a sampler argument: only the ones it
 * samples with count. Each has the filter and wrap Luxium would bind for that name
 * (CustomTextures.builtInSampler: render targets linear and clamped as in Iris, depth and
 * shadow maps nearest, the pack's images as their .mcmeta says). The draw-time side is
 * {@link MetalRenderPassSamplerMixin}. Pipelines with 16 textures or fewer are untouched.
 * <p>
 * Targets Metallum 467d312b.
 */
@Mixin(targets = "com.metallum.render.MetalCrossShaderCompiler", remap = false)
public abstract class MetalSamplerSlotsMixin {
    /**
     * Per compile thread: [0] the resource the loop is on (each binding is looked up by its id just
     * before it's added), [1] 1 when this pipeline has more than 16 textures.
     */
    private static final ThreadLocal<int[]> LUXIUM$STATE = ThreadLocal.withInitial(() -> new int[2]);

    @Inject(method = "remapResourceBindings", at = @At("HEAD"))
    private static void luxium$countTextures(MemoryStack stack, long compiler, int[] metalIndices, CallbackInfo ci) {
        int highest = -1;
        for (int index : metalIndices) {
            highest = Math.max(highest, index);
        }
        // Buffers are numbered apart and never reach 16, so an index of 16 is a 17th texture.
        LUXIUM$STATE.get()[1] = highest >= CustomTextures.SAMPLER_SLOTS ? 1 : 0;
    }

    @Redirect(method = "remapResourceBindings", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/util/spvc/Spvc;spvc_compiler_get_decoration(JII)I"))
    private static int luxium$rememberId(long compiler, int id, int decoration) {
        LUXIUM$STATE.get()[0] = id;
        return Spvc.spvc_compiler_get_decoration(compiler, id, decoration);
    }

    @Redirect(method = "remapResourceBindings", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/util/spvc/Spvc;spvc_compiler_msl_add_resource_binding(JLorg/lwjgl/util/spvc/SpvcMslResourceBinding;)I"))
    private static int luxium$constexprSamplers(long compiler, SpvcMslResourceBinding binding) {
        int result = Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding);
        if (result == Spvc.SPVC_SUCCESS && LUXIUM$STATE.get()[1] == 1) {
            String name = Spvc.spvc_compiler_get_name(compiler, LUXIUM$STATE.get()[0]);
            boolean[] filtering = CustomTextures.builtInSampler(name != null ? name : "");
            int filter = filtering[0] ? Spvc.SPVC_MSL_SAMPLER_FILTER_LINEAR : Spvc.SPVC_MSL_SAMPLER_FILTER_NEAREST;
            int address = filtering[1] ? Spvc.SPVC_MSL_SAMPLER_ADDRESS_REPEAT : Spvc.SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SpvcMslConstexprSampler s = SpvcMslConstexprSampler.calloc(stack);
                Spvc.spvc_msl_constexpr_sampler_init(s);
                s.min_filter(filter)
                        .mag_filter(filter)
                        .mip_filter(Spvc.SPVC_MSL_SAMPLER_MIP_FILTER_NONE)
                        .s_address(address)
                        .t_address(address)
                        .r_address(address);
                return Spvc.spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, binding.desc_set(), binding.binding(), s);
            }
        }
        return result;
    }
}
