package io.github.hecbatista.metalshaders.sodium;

/**
 * Extra data carried on Sodium's ChunkVertexEncoder.Vertex (added by ChunkVertexMixin), so that
 * quads the translucent sorter copies and writes later still know their block id.
 */
public interface LuxiumVertexData {
    int luxium$blockId();

    void luxium$setBlockId(int encoded);
}
