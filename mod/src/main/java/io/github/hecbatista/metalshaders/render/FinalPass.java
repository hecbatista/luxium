package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
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
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.glsl.ProgramTranslator;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import io.github.hecbatista.metalshaders.uniform.UniformWriter;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Step 6b: the pack's {@code final} program, drawn over the finished world.
 * <p>
 * There is no composite chain yet, so the inputs are stand-ins: colortex0 and colortex3 (what
 * Complementary's final reads) get a copy of the world image; noisetex gets a neutral value
 * (0.25, which cancels Complementary's dither); depth and shadow textures read as "far away";
 * everything else is black. Every texture the program declares has to be bound, because the
 * game checks all of them at draw time (FrontendRenderPass.java:486-490).
 */
public final class FinalPass {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("metalshaders", "pack/final");
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int NOISE_NEUTRAL = 0xFF404040;   // 64/255 ~ 0.25 in every channel

    private static ProgramTranslator.Result program;
    private static Std140Layout.Layout layout;
    private static final Map<String, String> defaults = new HashMap<>();
    private static CompiledRenderPipeline compiled;
    private static MappableRingBuffer ubo;
    private static GpuTexture worldCopy;
    private static GpuTextureView worldCopyView;
    private static final Map<Integer, GpuTextureView> solid = new HashMap<>();
    private static boolean failed;
    private static boolean loggedMissing;

    private FinalPass() {
    }

    public static boolean ready() {
        return compiled != null && !failed;
    }

    /** Compiles the translated program. Run on the render thread before the first draw. */
    public static void prepare(String vertexGlsl, String fragmentGlsl) {
        try {
            program = ProgramTranslator.translate(vertexGlsl, fragmentGlsl, ProgramTranslator.Mode.FULLSCREEN);
        } catch (ProgramTranslator.TranslationException e) {
            fail("translation failed: " + e.getMessage(), null);
            return;
        }
        layout = Std140Layout.of(program.uniforms());
        for (ProgramTranslator.Uniform u : program.uniforms()) {
            if (u.defaultValue() != null) {
                defaults.put(u.name(), u.defaultValue());
            }
        }

        BindGroupLayout.Builder bindings = BindGroupLayout.builder();
        if (!program.uniforms().isEmpty()) {
            bindings.withUniform(ProgramTranslator.UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
        }
        for (ProgramTranslator.Sampler s : program.samplers()) {
            bindings.withUniform(s.name(), UniformType.COMBINED_IMAGE_SAMPLER);
        }
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/pack_final"))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(ColorTargetState.DEFAULT)   // RGBA8, no blending: final replaces the image
                .build();
        LuxiumShaderSource source = new LuxiumShaderSource()
                .put(SHADER, ShaderType.VERTEX, program.vertex())
                .put(SHADER, ShaderType.FRAGMENT, program.fragment());
        try {
            compiled = RenderSystem.getDevice().compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
        } catch (RuntimeException e) {
            fail("compile threw", e);
            return;
        }
        if (compiled == null) {
            fail("the device returned no pipeline (the compiler's reason is logged above)", null);
            return;
        }
        if (layout.size() > 0) {
            ubo = new MappableRingBuffer(() -> "Luxium final uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, layout.size());
        }
        MetalShadersClient.LOGGER.info("Pack final program compiled: {} uniforms ({} bytes), {} samplers",
                program.uniforms().size(), layout.size(), program.samplers().size());
    }

    /** Draws final over the world. Called right after the world is drawn, with no pass open. */
    public static void draw(RenderTarget target, Function<String, Object> values) {
        if (!ready()) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            int w = target.width;
            int h = target.height;
            ensureWorldCopy(device, w, h);
            encoder.copyTextureToTexture(target.getColorTexture(), worldCopy, 0, 0, 0, 0, 0, w, h);

            if (ubo != null) {
                try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
                    Set<String> missing = UniformWriter.write(layout, values, defaults, view.data());
                    if (!loggedMissing) {
                        loggedMissing = true;
                        MetalShadersClient.LOGGER.info("Final pass: values not supplied yet (zero or pack default): {}", missing);
                    }
                }
            }

            // Resolve every input first: making a placeholder uploads to a texture, and no other
            // command may run while a render pass is open.
            Map<String, GpuTextureView> inputs = new HashMap<>();
            for (ProgramTranslator.Sampler s : program.samplers()) {
                inputs.put(s.name(), inputFor(device, encoder, s.name()));
            }

            var samplerCache = RenderSystem.getSamplerCache();
            try (RenderPass pass = encoder.createRenderPass(() -> "Luxium pack final", target.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(compiled);
                if (ubo != null) {
                    pass.setUniform(ProgramTranslator.UNIFORM_BLOCK, ubo.currentBuffer());
                }
                for (ProgramTranslator.Sampler s : program.samplers()) {
                    boolean depth = s.name().startsWith("depthtex") || s.name().startsWith("shadowtex");
                    pass.setUniform(s.name(), inputs.get(s.name()),
                            samplerCache.getClampToEdge(depth ? FilterMode.NEAREST : FilterMode.LINEAR));
                }
                pass.draw(3, 1, 0, 0);
            }
            if (ubo != null) {
                ubo.rotate();
            }
            maybeMeasure(device, encoder, target, values);
        } catch (RuntimeException e) {
            fail("drawing threw", e);
        }
    }

    // ---- Measurement: how much did final change the image? -----------------------------------

    private static long lastMeasureNanos;
    private static boolean measuring;

    /**
     * Every 5 seconds, reads back the image before final (our world copy) and after it (the main
     * target) and logs how many pixels differ. A debugging aid: effects like Complementary's
     * underwater distortion move the image by about a pixel, too little to judge by eye.
     */
    private static void maybeMeasure(GpuDevice device, CommandEncoder encoder, RenderTarget target, Function<String, Object> values) {
        long now = System.nanoTime();
        if (measuring || now - lastMeasureNanos < 5_000_000_000L) {
            return;
        }
        lastMeasureNanos = now;
        measuring = true;
        int w = target.width;
        int h = target.height;
        long size = (long) w * h * 4;
        GpuBuffer before = device.createBuffer(() -> "Luxium measure before", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
        GpuBuffer after = device.createBuffer(() -> "Luxium measure after", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
        Object underwater = values.apply("isEyeInWater");
        int[] done = {0};
        Runnable compare = () -> {
            if (++done[0] < 2) {
                return;
            }
            try (GpuBufferSlice.MappedView a = before.map(true, false); GpuBufferSlice.MappedView b = after.map(true, false)) {
                ByteBuffer x = a.data();
                ByteBuffer y = b.data();
                long changed = 0;
                long sum = 0;
                for (int i = 0; i + 3 < size; i += 4) {
                    int d = Math.abs((x.get(i) & 0xFF) - (y.get(i) & 0xFF))
                            + Math.abs((x.get(i + 1) & 0xFF) - (y.get(i + 1) & 0xFF))
                            + Math.abs((x.get(i + 2) & 0xFF) - (y.get(i + 2) & 0xFF));
                    if (d > 0) {
                        changed++;
                        sum += d;
                    }
                }
                long pixels = (long) w * h;
                MetalShadersClient.LOGGER.info(
                        "Final pass measurement (isEyeInWater={}): {}% of pixels changed, average change {} (0-765) where changed",
                        underwater, String.format("%.1f", 100.0 * changed / pixels),
                        changed == 0 ? "0" : String.format("%.1f", (double) sum / changed));
            } finally {
                before.close();
                after.close();
                measuring = false;
            }
        };
        encoder.copyTextureToBuffer(worldCopy, before, 0, compare, 0);
        encoder.copyTextureToBuffer(target.getColorTexture(), after, 0, compare, 0);
    }

    private static GpuTextureView inputFor(GpuDevice device, CommandEncoder encoder, String name) {
        if (name.equals("colortex0") || name.equals("colortex3")) {
            return worldCopyView;
        }
        if (name.equals("noisetex")) {
            return solid(device, encoder, NOISE_NEUTRAL);
        }
        if (name.startsWith("depthtex") || name.startsWith("shadowtex")) {
            return solid(device, encoder, WHITE);
        }
        return solid(device, encoder, BLACK);
    }

    private static void ensureWorldCopy(GpuDevice device, int w, int h) {
        if (worldCopy != null && worldCopy.getWidth(0) == w && worldCopy.getHeight(0) == h) {
            return;
        }
        if (worldCopy != null) {
            worldCopyView.close();
            worldCopy.close();
        }
        worldCopy = device.createTexture("Luxium world copy",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        worldCopyView = device.createTextureView(worldCopy);
    }

    /** A 1x1 texture of one color (ARGB), made once. */
    private static GpuTextureView solid(GpuDevice device, CommandEncoder encoder, int argb) {
        return solid.computeIfAbsent(argb, color -> {
            GpuTexture t = device.createTexture("Luxium placeholder " + Integer.toHexString(color),
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
            ByteBuffer rgba = ByteBuffer.allocateDirect(4);
            int c = color;
            rgba.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24)).flip();
            encoder.writeToTexture(t, rgba, 0, 0, 0, 0, 1, 1);
            return device.createTextureView(t);
        });
    }

    private static void fail(String why, Throwable e) {
        failed = true;
        if (e == null) {
            MetalShadersClient.LOGGER.error("Pack final pass disabled: {}", why);
        } else {
            MetalShadersClient.LOGGER.error("Pack final pass disabled: {}", why, e);
        }
    }
}
