package io.github.hecbatista.metalshaders.mixin.metallum;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Step 7g: the MSL in Metallum's package-private {@code MetalCrossShaderCompiler.Compiled} record. */
@Mixin(targets = "com.metallum.render.MetalCrossShaderCompiler$Compiled", remap = false)
public interface MetallumCompiledAccessor {
    @Accessor("vertexSource")
    String luxium$vertexSource();

    @Accessor("fragmentSource")
    String luxium$fragmentSource();
}
