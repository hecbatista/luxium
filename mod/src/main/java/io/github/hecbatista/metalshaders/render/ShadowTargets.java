package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shadow map (step 6e): a square depth image at the pack's shadowMapResolution, the
 * shadowcolor buffers the shadow program writes, and a float copy of the depth for the pack's
 * shadowtex0/1 (read with Luxium's arithmetic shadow comparison). Forward depth, cleared to 1
 * (Vitrail sky-and-shadows.md). v1 draws opaque terrain only, so shadowtex0 and shadowtex1 are the
 * same image (shadowtex1 is "without translucents").
 */
public final class ShadowTargets {
    /** Iris's default shadowcolor format (RGBA, 8 bits a channel). */
    public static final GpuFormat FORMAT = GpuFormat.RGBA8_UNORM;

    private static int size;
    private static GpuTexture depth;
    private static GpuTextureView depthView;
    private static GpuTexture depthSampled;
    private static GpuTextureView depthSampledView;
    private static final Map<Integer, GpuTexture> colors = new HashMap<>();
    private static final Map<Integer, GpuTextureView> colorViews = new HashMap<>();
    private static CompiledRenderPipeline copyPipeline;
    private static boolean drawn;

    private ShadowTargets() {
    }

    static void ensure(int resolution, List<Integer> writes) {
        GpuDevice device = RenderSystem.getDevice();
        if (resolution != size) {
            close();
            size = resolution;
            int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
            depth = device.createTexture("Luxium shadow depth", usage, GpuFormat.D32_FLOAT, size, size, 1, 1);
            depthView = device.createTextureView(depth);
            depthSampled = device.createTexture("Luxium shadowtex (sampled)", usage, GpuFormat.R32_FLOAT, size, size, 1, 1);
            depthSampledView = device.createTextureView(depthSampled);
            MetalShadersClient.LOGGER.info("Shadow map allocated at {}x{}", size, size);
        }
        for (int index : writes) {
            if (!colors.containsKey(index)) {
                GpuTexture t = device.createTexture("Luxium shadowcolor" + index,
                        GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                        FORMAT, size, size, 1, 1);
                colors.put(index, t);
                colorViews.put(index, device.createTextureView(t));
            }
        }
    }

    private static void close() {
        if (depth != null) {
            depthView.close();
            depth.close();
            depthSampledView.close();
            depthSampled.close();
        }
        colorViews.values().forEach(GpuTextureView::close);
        colors.values().forEach(GpuTexture::close);
        colorViews.clear();
        colors.clear();
        drawn = false;
    }

    /** Frame start of the shadow pass: depth to 1 (far), colours to white (Iris shadowcolorNClearColor default). */
    static void clear() {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearDepthTexture(depth, 1.0);
        for (GpuTexture t : colors.values()) {
            encoder.clearColorTexture(t, new Vector4f(1, 1, 1, 1));
        }
    }

    static GpuTextureView color(int index) {
        return colorViews.get(index);
    }

    static GpuTextureView depthAttachment() {
        return depthView;
    }

    /** After the shadow pass: the depth copied into a float image the pack's passes sample. */
    static void publish() {
        GpuDevice device = RenderSystem.getDevice();
        if (copyPipeline == null) {
            Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/shadow_depth_copy");
            String vertex = """
                    #version 450
                    layout(location = 0) out vec2 uv;
                    void main() {
                        vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                        uv = p;
                        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """;
            String fragment = """
                    #version 450
                    uniform sampler2D shadowDepth;
                    layout(location = 0) in vec2 uv;
                    layout(location = 0) out vec4 depth;
                    void main() { depth = vec4(texture(shadowDepth, uv).r); }
                    """;
            copyPipeline = device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/shadow_depth_copy"))
                            .withVertexShader(shader).withFragmentShader(shader)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayout.builder().withUniform("shadowDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                            .build(),
                    new LuxiumShaderSource().put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment),
                    Runnable::run).join().finishCompile();
        }
        try (RenderPass rp = device.createCommandEncoder().createRenderPass(() -> "Luxium shadow depth copy", depthSampledView, Optional.empty())) {
            rp.setPipeline(copyPipeline);
            rp.setUniform("shadowDepth", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            rp.draw(3, 1, 0, 0);
        }
        drawn = true;
    }

    /**
     * What a shadow sampler name reads, or null for the placeholder: shadowtex0/1, shadow,
     * watershadow -> the depth; shadowcolor0/1, shadowcolor -> the colours.
     */
    public static GpuTextureView input(String name) {
        if (!drawn) {
            return null;
        }
        return switch (name) {
            case "shadowtex0", "shadowtex1", "shadow", "watershadow" -> depthSampledView;
            case "shadowcolor", "shadowcolor0" -> colorViews.get(0);
            case "shadowcolor1" -> colorViews.get(1);
            default -> null;
        };
    }
}
