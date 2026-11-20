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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * A pass for a renderer that draws several pipelines through one pass handed to it (the particle
 * renderer: QuadParticleFeatureRenderer.executeGroup binds its shared uniforms once, then draws a
 * layer per pipeline). Each setPipeline decides where the following draw goes: a pipeline of the
 * pack's into the pack's pass ({@link EntityTakeover#particleHold}), any other into the level's
 * pass. The uniforms set before the first pipeline (the default uniforms, DynamicTransforms, the
 * lightmap) are kept and set again on whichever pass the draw goes to.
 */
public final class RoutingPass implements RenderPass {
    private final LevelPass level;
    private final List<Consumer<RenderPass>> shared = new ArrayList<>();
    private @Nullable RenderPass current;
    private boolean pipelineSeen;

    public RoutingPass(LevelPass level) {
        this.level = level;
    }

    /** Ends the routing: the pack's pass closes (the level's reopens on its next call). */
    public void finish() {
        EntityTakeover.endParticleDraw();
        EntityTakeover.releaseHold();
        this.current = null;
    }

    private RenderPass current() {
        return this.current != null ? this.current : this.level;
    }

    @Override
    public void setPipeline(CompiledRenderPipeline pipeline) {
        this.pipelineSeen = true;
        RenderPass target;
        if (EntityTakeover.isPackParticlePipeline(pipeline)) {
            this.level.suspend();
            target = EntityTakeover.particleHold();
        } else {
            EntityTakeover.endParticleDraw();
            EntityTakeover.releaseHold();
            target = this.level;
        }
        if (target != this.current) {
            this.current = target;
            for (Consumer<RenderPass> s : this.shared) {
                s.accept(target);
            }
        }
        target.setPipeline(pipeline);
        EntityTakeover.bind(target, pipeline);
    }

    private void setShared(Consumer<RenderPass> call) {
        if (!this.pipelineSeen) {
            this.shared.add(call);
        }
        call.accept(current());
    }

    @Override
    public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        setShared(p -> p.setUniform(name, textureView, sampler));
    }

    @Override
    public void setUniform(String name, GpuBuffer value) {
        setShared(p -> p.setUniform(name, value));
    }

    @Override
    public void setUniform(String name, GpuBufferSlice value) {
        setShared(p -> p.setUniform(name, value));
    }

    // Debug groups stay on the level's pass, which keeps them across its reopenings.
    @Override
    public void pushDebugGroup(java.util.function.Supplier<String> label) {
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void close() {
        finish();
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        current().writeTimestamp(pool, index);
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        current().pushConstants(value);
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        current().enableScissor(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        current().disableScissor();
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        current().setVertexBuffer(slot, vertexBuffer);
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
        current().setIndexBuffer(indexBuffer, indexType);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (indexCount == 0 || instanceCount == 0) {
            // An empty draw does nothing, but Metal's validation refuses it and Metallum passes it
            // on (vanilla's weather draws snow with 0 columns while it only rains).
            return;
        }
        current().drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        current().multiDrawIndexed(drawParameters, instanceCount, firstInstance, drawCount);
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        current().multiDrawIndexed(firstIndexOffsets, indexCounts, vertexOffsets, drawCount);
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        current().drawIndexedIndirect(commands, drawCount);
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer,
                                        @Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T uniformArgument) {
        current().drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (vertexCount == 0 || instanceCount == 0) {
            return;   // see drawIndexed
        }
        current().draw(vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        current().multiDraw(drawParameters, instanceCount, firstInstance, drawCount);
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        current().multiDraw(firstVertices, vertexCounts, drawCount);
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        current().drawIndirect(commands, drawCount);
    }
}
