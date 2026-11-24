package io.github.hecbatista.metalshaders.mixin.sodium;

import io.github.hecbatista.metalshaders.sodium.LuxiumChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every Sodium chunk mesh uses Luxium's wider vertex (LuxiumChunkVertex). In Sodium 0.9.2 the
 * format is asked for by RenderSectionManager (renderer and mesh builder), RenderRegion (stride)
 * and ArenaAggregator (the shared geometry arena's stride), all through this one method.
 * They read it only while Sodium builds its section manager, so the answer changes only together
 * with a rebuild of every chunk (MetalShadersClient.luxiumMeshFormat). With no pack in use (shaders
 * off, or no pack) Sodium keeps its own format, so "off" leaves Sodium as it is without Luxium.
 */
@Mixin(value = ChunkMeshFormats.class, remap = false)
abstract class ChunkMeshFormatsMixin {
    @Inject(method = "getCurrent", at = @At("HEAD"), cancellable = true)
    private static void luxium$format(CallbackInfoReturnable<ChunkVertexType> cir) {
        if (!io.github.hecbatista.metalshaders.MetalShadersClient.luxiumMeshFormat()) {
            return;
        }
        cir.setReturnValue(LuxiumChunkVertex.INSTANCE);
    }
}
