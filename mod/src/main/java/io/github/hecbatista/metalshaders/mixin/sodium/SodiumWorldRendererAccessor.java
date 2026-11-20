package io.github.hecbatista.metalshaders.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sodium 0.9.2's SodiumWorldRenderer (decompiled): its section manager and this frame's fog. */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface SodiumWorldRendererAccessor {
    @Accessor("renderSectionManager")
    RenderSectionManager luxium$renderSectionManager();

    @Accessor("lastFogParameters")
    FogParameters luxium$lastFogParameters();
}
