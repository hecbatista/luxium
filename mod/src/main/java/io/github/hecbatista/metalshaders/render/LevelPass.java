package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * The game's "Main" pass, made one the pack's own passes can step into the middle of.
 * <p>
 * 26.3 opens one pass on the main target for the whole opaque part of the level and hands it to
 * every renderer (LevelRenderer.addMainPass). The pack's entities need passes of their own on the
 * pack's targets in the middle of that, and only one pass can be open at a time. So the game is
 * handed this instead: every call is forwarded to a real pass on the same attachments, and
 * {@link #suspend} closes that real pass whenever a pack pass needs the gap; the next call the
 * game makes opens a real one again (never clearing), with the default uniforms bound, the debug
 * groups pushed again and the scissor set again: the state a phase keeps across draws. Pipeline,
 * buffers and uniforms are set by each draw. Vitrail does the same (render/LevelPass, see NOTICE).
 */
public final class LevelPass implements RenderPass {
    private final Supplier<String> label;
    private final GpuTextureView color;
    private final @Nullable GpuTextureView depth;
    private final List<Supplier<String>> groups = new ArrayList<>();
    private @Nullable RenderPass real;
    private int @Nullable [] scissor;
    private boolean closed;

    public LevelPass(RenderPass first, Supplier<String> label, GpuTextureView color, @Nullable GpuTextureView depth) {
        this.real = first;
        this.label = label;
        this.color = color;
        this.depth = depth;
    }

    /** Closes the real pass, if one is open, so another pass can be opened. */
    public void suspend() {
        RenderPass r = this.real;
        if (r == null) {
            return;
        }
        for (int i = 0; i < this.groups.size(); i++) {
            r.popDebugGroup();   // a pass may not close with debug groups open
        }
        r.close();
        this.real = null;
    }

    private RenderPass real() {
        if (this.closed) {
            throw new IllegalStateException("Luxium: the level pass was used after it was closed");
        }
        if (this.real == null) {
            EntityTakeover.releaseHold();
            RenderPass r = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(this.label, this.color, Optional.empty(), this.depth, OptionalDouble.empty());
            RenderSystem.bindDefaultUniforms(r);
            for (Supplier<String> g : this.groups) {
                r.pushDebugGroup(g);
            }
            if (this.scissor != null) {
                r.enableScissor(this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
            }
            this.real = r;
        }
        return this.real;
    }

    @Override
    public void close() {
        suspend();
        this.closed = true;
    }

    @Override
    public void pushDebugGroup(Supplier<String> group) {
        real().pushDebugGroup(group);
        this.groups.add(group);
    }

    @Override
    public void popDebugGroup() {
        real().popDebugGroup();
        this.groups.removeLast();
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        real().writeTimestamp(pool, index);
    }

    @Override
    public void setPipeline(CompiledRenderPipeline pipeline) {
        real().setPipeline(pipeline);
    }

    @Override
    public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        real().setUniform(name, textureView, sampler);
    }

    @Override
    public void setUniform(String name, GpuBuffer value) {
        real().setUniform(name, value);
    }

    @Override
    public void setUniform(String name, GpuBufferSlice value) {
        real().setUniform(name, value);
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        real().pushConstants(value);
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        real().enableScissor(x, y, width, height);
        this.scissor = new int[]{x, y, width, height};
    }

    @Override
    public void disableScissor() {
        real().disableScissor();
        this.scissor = null;
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        real().setVertexBuffer(slot, vertexBuffer);
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
        real().setIndexBuffer(indexBuffer, indexType);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (indexCount == 0 || instanceCount == 0) {
            // An empty draw does nothing, but Metal's validation refuses it and Metallum passes it
            // on (vanilla's weather draws snow with 0 columns while it only rains).
            return;
        }
        real().drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        real().multiDrawIndexed(drawParameters, instanceCount, firstInstance, drawCount);
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        real().multiDrawIndexed(firstIndexOffsets, indexCounts, vertexOffsets, drawCount);
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        real().drawIndexedIndirect(commands, drawCount);
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer,
                                        @Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T uniformArgument) {
        real().drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (vertexCount == 0 || instanceCount == 0) {
            return;   // see drawIndexed
        }
        real().draw(vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        real().multiDraw(drawParameters, instanceCount, firstInstance, drawCount);
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        real().multiDraw(firstVertices, vertexCounts, drawCount);
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        real().drawIndirect(commands, drawCount);
    }
}
