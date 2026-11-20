package io.github.hecbatista.metalshaders.render;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Hands our own GLSL to the game's pipeline compiler. {@code GpuDevice.compilePipeline} asks a
 * ShaderSource for each stage by the id the pipeline names; vanilla's answers from resource
 * packs, this one from a map we fill (later: the translated pack programs).
 * No {@code #include} support: our sources arrive already expanded.
 */
public final class LuxiumShaderSource implements ShaderSource {
    private record Key(Identifier id, ShaderType type) {
    }

    private final Map<Key, String> sources = new HashMap<>();

    public LuxiumShaderSource put(Identifier id, ShaderType type, String glsl) {
        sources.put(new Key(id, type), glsl);
        return this;
    }

    @Override
    public @Nullable String getShader(Identifier id, ShaderType type) {
        return sources.get(new Key(id, type));
    }

    @Override
    public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
        return null;
    }

    @Override
    public void close() {
    }
}
