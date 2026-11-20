package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Step 6a: the smallest possible full-screen pass, to prove our own GLSL reaches the screen
 * through the game's compiler and Metallum. It blends a see-through gradient over the finished
 * world: blue at the top of the screen, red at the bottom. If the colors come out the other way
 * round, the vertical axis flips somewhere on the way to Metal.
 * <p>
 * Drawn like vanilla's post passes (PostPass.java): one triangle that covers the screen, made in
 * the vertex shader from gl_VertexIndex, so no vertex buffer is needed.
 */
public final class TestPass {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("metalshaders", "test_gradient");

    private static final String VERTEX = """
            #version 450
            layout(location = 0) out vec2 uv;
            void main() {
                // Vertices 0, 1, 2 -> (0,0), (2,0), (0,2): one triangle that covers the whole screen.
                vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                uv = p;                                   // 0..1 on screen, y = 1 is the top in OpenGL terms
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 450
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 color;
            void main() {
                vec3 top = vec3(0.1, 0.3, 1.0);      // blue
                vec3 bottom = vec3(1.0, 0.15, 0.1);  // red
                color = vec4(mix(bottom, top, clamp(uv.y, 0.0, 1.0)), 0.35);
            }
            """;

    private static CompiledRenderPipeline compiled;
    private static boolean failed;

    private TestPass() {
    }

    /** Called right after the world is drawn, while no render pass is open. */
    public static void draw(RenderTarget target) {
        if (failed) {
            return;
        }
        if (compiled == null && !compile()) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Luxium test gradient", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(compiled);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static boolean compile() {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/test_gradient"))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                // Same pixel format as the main target (MainTarget.java:21), blended over what's there.
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .build();
        LuxiumShaderSource source = new LuxiumShaderSource()
                .put(SHADER, ShaderType.VERTEX, VERTEX)
                .put(SHADER, ShaderType.FRAGMENT, FRAGMENT);
        try {
            compiled = RenderSystem.getDevice().compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
        } catch (RuntimeException e) {
            MetalShadersClient.LOGGER.error("Test pass failed to compile", e);
        }
        if (compiled == null) {
            failed = true;
            MetalShadersClient.LOGGER.error("Test pass: the device returned no pipeline (see errors above); disabled");
            return false;
        }
        MetalShadersClient.LOGGER.info("Test pass compiled through the game's compiler and the Metal backend");
        return true;
    }
}
