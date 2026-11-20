package io.github.hecbatista.metalshaders.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * A pass that draws nothing: the level's solid features are replayed into the shadow map, and
 * what the pack's shadow program doesn't serve must go nowhere rather than onto the screen
 * (Vitrail sky-and-shadows.md: inside the light's walk a refusal is a drop).
 */
public final class DropPass implements RenderPass {
    public static final DropPass INSTANCE = new DropPass();

    private DropPass() {
    }

    @Override public void pushDebugGroup(Supplier<String> label) { }
    @Override public void popDebugGroup() { }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) { }
    @Override public void setPipeline(CompiledRenderPipeline pipeline) { }
    @Override public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) { }
    @Override public void setUniform(String name, GpuBuffer value) { }
    @Override public void setUniform(String name, GpuBufferSlice value) { }
    @Override public void pushConstants(ByteBuffer value) { }
    @Override public void enableScissor(int x, int y, int width, int height) { }
    @Override public void disableScissor() { }
    @Override public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) { }
    @Override public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) { }
    @Override public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) { }
    @Override public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) { }
    @Override public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) { }
    @Override public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) { }
    @Override public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer,
                                                  @Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T uniformArgument) { }
    @Override public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) { }
    @Override public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) { }
    @Override public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) { }
    @Override public void drawIndirect(GpuBufferSlice commands, int drawCount) { }
    @Override public void close() { }
}
