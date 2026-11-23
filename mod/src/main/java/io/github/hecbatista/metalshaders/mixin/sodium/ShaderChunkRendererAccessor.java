package io.github.hecbatista.metalshaders.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Sodium 0.9.2's ShaderChunkRenderer (decompiled) keeps its terrain pipelines in two static maps,
 * by terrain pass: {@code programs} and {@code oitPrograms} (OitPipelineSet). Each is built once,
 * with the vertex format of the renderer that asked first, and reused by every later renderer.
 * SodiumPrograms swaps their contents when the chunk format changes (Iris instead always builds
 * them with Sodium's own format, MixinShaderChunkRenderer).
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public interface ShaderChunkRendererAccessor {
    @Accessor("programs")
    static Map<Object, Object> luxium$programs() {
        throw new AssertionError();
    }

    @Accessor("oitPrograms")
    static Map<Object, Object> luxium$oitPrograms() {
        throw new AssertionError();
    }
}
