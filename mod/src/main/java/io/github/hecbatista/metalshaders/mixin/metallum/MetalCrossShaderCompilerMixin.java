package io.github.hecbatista.metalshaders.mixin.metallum;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import io.github.hecbatista.metalshaders.render.PipelineCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Step 7g: once Metallum has turned one of our pipelines' SPIR-V into MSL (on a Luxium compile
 * thread), compile that MSL into a Metal library there and drop it. Metal caches the result on
 * disk, so Metallum's own compile of the same source later, on the render thread
 * ({@code MetalDevice.getOrCompileFunction}, behind a lock), is a cache hit. Measured cold on
 * Complementary without validation, that render-thread step was 7-8 s per world.
 * <p>
 * Also writes the MSL out under {@code LUXIUM_DUMP_SHADERS} (see PipelineCompiler.DUMP).
 * <p>
 * Targets Metallum 467d312b ({@code MetalCrossShaderCompiler.compile} returns its package-private
 * {@code Compiled} record). Nothing here changes what Metallum compiles or draws.
 */
@Mixin(targets = "com.metallum.render.MetalCrossShaderCompiler", remap = false)
public abstract class MetalCrossShaderCompilerMixin {
    @Inject(method = "compile", at = @At("RETURN"))
    private static void luxium$warmMetal(BackendRenderPipeline.CreateInfo info, CallbackInfoReturnable<Object> cir) {
        if (!info.name().startsWith("metalshaders:") || !(cir.getReturnValue() instanceof MetallumCompiledAccessor compiled)) {
            return;
        }
        if (PipelineCompiler.dumping()) {
            PipelineCompiler.dump(info.name(), ".vert.metal", compiled.luxium$vertexSource());
            PipelineCompiler.dump(info.name(), ".frag.metal", compiled.luxium$fragmentSource());
        }
        if (PipelineCompiler.onPoolThread()) {
            PipelineCompiler.warmMetal(compiled.luxium$vertexSource());
            PipelineCompiler.warmMetal(compiled.luxium$fragmentSource());
        }
    }
}
