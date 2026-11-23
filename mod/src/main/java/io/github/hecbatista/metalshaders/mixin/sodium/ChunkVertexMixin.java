package io.github.hecbatista.metalshaders.mixin.sodium;

import io.github.hecbatista.metalshaders.sodium.LuxiumChunkVertex;
import io.github.hecbatista.metalshaders.sodium.LuxiumVertexData;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives Sodium 0.9.2's vertex object a block id. Quads pushed right away are encoded while their
 * block is being meshed and read the id from LuxiumChunkVertex's thread-local; a translucent quad
 * kept for sorting (FullTQuad, the BSP splitter) is copied with copyVertexTo and written later,
 * so the copy takes the id along (Vitrail's ChunkVertexMixin does the same, see NOTICE). A copy
 * made from a vertex with no id yet takes the id of the block being meshed now.
 */
@Mixin(value = ChunkVertexEncoder.Vertex.class, remap = false)
abstract class ChunkVertexMixin implements LuxiumVertexData {
    @Unique
    private int luxium$blockId;

    @Override
    public int luxium$blockId() {
        return this.luxium$blockId;
    }

    @Override
    public void luxium$setBlockId(int encoded) {
        this.luxium$blockId = encoded;
    }

    @Inject(method = "copyVertexTo", at = @At("TAIL"))
    private static void luxium$copyId(ChunkVertexEncoder.Vertex from, ChunkVertexEncoder.Vertex to, CallbackInfo ci) {
        int id = ((LuxiumVertexData) from).luxium$blockId();
        ((LuxiumVertexData) to).luxium$setBlockId(id != 0 ? id : LuxiumChunkVertex.currentBlockId());
    }
}
