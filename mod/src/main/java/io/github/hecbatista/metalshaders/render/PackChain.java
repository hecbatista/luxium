package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
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
import io.github.hecbatista.metalshaders.glsl.ProgramTranslator;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import io.github.hecbatista.metalshaders.pack.RenderTargetPlan;
import io.github.hecbatista.metalshaders.uniform.UniformWriter;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Step 6c: the pack's full-screen chain (deferred*, composite*, final), run after the world.
 * <p>
 * Each colortex buffer has two halves. A pass reads every buffer's current half and writes the
 * other half of the buffers its DRAWBUFFERS names; afterwards those buffers swap, so the next
 * pass reads what was just written (the flip, Vitrail docs/internals/render-targets.md). Buffers
 * the pack marks cleared are cleared at the start of each frame; the others keep last frame's
 * contents. final writes to the screen.
 * <p>
 * Stand-ins until the pack's own world shaders run (6d): colortex0 starts each frame as a copy of
 * vanilla's image; depthtex0/1/2 are vanilla's depth converted to the OpenGL convention;
 * shadow textures are placeholders.
 */
public final class PackChain {
    /** One compiled pass. */
    private record Pass(String name, ProgramTranslator.Result program, Std140Layout.Layout layout,
                        Map<String, String> defaults, CompiledRenderPipeline pipeline, MappableRingBuffer ubo,
                        List<Integer> writes, float scale) {
    }

    private static final Map<String, Integer> LEGACY = Map.of(
            "gcolor", 0, "gdepth", 1, "gnormal", 2, "composite", 3, "gaux1", 4, "gaux2", 5, "gaux3", 6, "gaux4", 7);

    private static RenderTargetPlan plan;
    private static final List<Pass> passes = new ArrayList<>();
    private static final Map<Integer, GpuTexture[]> halves = new HashMap<>();
    private static final Map<Integer, GpuTextureView[]> halfViews = new HashMap<>();
    private static final Map<Integer, Integer> current = new HashMap<>();
    private static GpuTexture depthCopy;
    private static GpuTextureView depthCopyView;
    private static GpuTextureView noise;
    private static final Map<Integer, GpuTextureView> solid = new HashMap<>();
    private static CompiledRenderPipeline seedPipeline;
    private static CompiledRenderPipeline depthPipeline;
    private static GpuTexture depthConverted;
    private static GpuTextureView depthConvertedView;
    private static boolean failed;
    private static int width;
    private static int height;

    private PackChain() {
    }

    public static boolean ready() {
        return !failed && !passes.isEmpty();
    }

    /**
     * Translates and compiles every pass. {@code programs} is name -> (vertex, fragment) GLSL,
     * already preprocessed, in run order; final last.
     */
    public static void prepare(RenderTargetPlan targetPlan, List<Map.Entry<String, String[]>> programs,
                               Map<String, Float> scales, NativeImage noiseImage) {
        plan = targetPlan;
        GpuDevice device = RenderSystem.getDevice();
        for (Map.Entry<String, String[]> p : programs) {
            String name = p.getKey();
            float scale = scales.getOrDefault(name, 1.0f);
            ProgramTranslator.Result r;
            try {
                r = ProgramTranslator.translate(p.getValue()[0], p.getValue()[1], ProgramTranslator.Mode.FULLSCREEN, scale);
            } catch (ProgramTranslator.TranslationException e) {
                fail(name + ": translation failed: " + e.getMessage(), null);
                return;
            }
            boolean isFinal = name.equals("final");
            List<Integer> writes = isFinal ? List.of() : r.drawBuffers();
            Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/" + name);
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/pack_" + name))
                    .withVertexShader(shader)
                    .withFragmentShader(shader)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
            if (isFinal) {
                builder.withColorTargetState(ColorTargetState.DEFAULT);
            } else {
                for (int i = 0; i < writes.size(); i++) {
                    RenderTargetPlan.Target t = plan.target(writes.get(i));
                    // A promoted buffer is written color-only, so its alpha stays at the cleared 1.
                    int mask = t.promoted() ? ColorTargetState.WRITE_COLOR : ColorTargetState.WRITE_ALL;
                    builder.withColorTargetState(i, new ColorTargetState(Optional.empty(), t.format(), mask));
                }
            }
            BindGroupLayout.Builder bindings = BindGroupLayout.builder();
            if (!r.uniforms().isEmpty()) {
                bindings.withUniform(ProgramTranslator.UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
            }
            for (ProgramTranslator.Sampler s : r.samplers()) {
                bindings.withUniform(s.name(), UniformType.COMBINED_IMAGE_SAMPLER);
            }
            builder.withBindGroupLayout(bindings.build());
            LuxiumShaderSource source = new LuxiumShaderSource()
                    .put(shader, ShaderType.VERTEX, r.vertex())
                    .put(shader, ShaderType.FRAGMENT, r.fragment());
            CompiledRenderPipeline compiled;
            try {
                compiled = device.compilePipeline(builder.build(), source, Runnable::run).join().finishCompile();
            } catch (RuntimeException e) {
                fail(name + ": compile threw", e);
                return;
            }
            if (compiled == null) {
                fail(name + ": the device returned no pipeline (reason logged above)", null);
                return;
            }
            Std140Layout.Layout layout = Std140Layout.of(r.uniforms());
            Map<String, String> defaults = new HashMap<>();
            r.uniforms().forEach(u -> {
                if (u.defaultValue() != null) {
                    defaults.put(u.name(), u.defaultValue());
                }
            });
            MappableRingBuffer ubo = layout.size() == 0 ? null
                    : new MappableRingBuffer(() -> "Luxium " + name + " uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, layout.size());
            passes.add(new Pass(name, r, layout, defaults, compiled, ubo, writes, scale));
            MetalShadersClient.LOGGER.info("Chain pass {}: writes {}, reads {}, scale {}", name, writes,
                    r.samplers().stream().map(ProgramTranslator.Sampler::name).toList(), scale);
        }
        if (noiseImage != null) {
            GpuTexture t = device.createTexture("Luxium noisetex", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, noiseImage.getWidth(), noiseImage.getHeight(), 1, 1);
            device.createCommandEncoder().writeToTexture(t, noiseImage);
            noise = device.createTextureView(t);
            noiseImage.close();
        }
        compileSeed(device);
    }

    /** A tiny pass that copies vanilla's image into colortex0, whatever colortex0's format. */
    private static void compileSeed(GpuDevice device) {
        if (failed) {
            return;
        }
        Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/seed");
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/seed"))
                .withVertexShader(shader).withFragmentShader(shader)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("world", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), plan.target(0).format(), ColorTargetState.WRITE_ALL))
                .build();
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
                uniform sampler2D world;
                layout(location = 0) in vec2 uv;
                layout(location = 0) out vec4 color;
                void main() { color = texture(world, uv); }
                """;
        seedPipeline = device.compilePipeline(pipeline, new LuxiumShaderSource()
                .put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment), Runnable::run).join().finishCompile();
        if (seedPipeline == null) {
            fail("seed pass failed to compile", null);
            return;
        }
        // depthtex0/1/2: the game draws reversed-Z (far = 0); packs expect OpenGL's convention.
        // Iris rewrites each depth read to 1 - depth (DepthTransformer); converting the image once
        // instead (Vitrail's way) also covers reads through helper functions. See NOTICE.
        Identifier depthShader = Identifier.fromNamespaceAndPath("metalshaders", "pack/depth_convert");
        String depthFragment = """
                #version 450
                uniform sampler2D reversedDepth;
                layout(location = 0) in vec2 uv;
                layout(location = 0) out vec4 depth;
                void main() { depth = vec4(1.0 - texture(reversedDepth, uv).r); }
                """;
        depthPipeline = device.compilePipeline(RenderPipeline.builder()
                        .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/depth_convert"))
                        .withVertexShader(depthShader).withFragmentShader(depthShader)
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                        .withBindGroupLayout(BindGroupLayout.builder().withUniform("reversedDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
                        .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                        .build(),
                new LuxiumShaderSource().put(depthShader, ShaderType.VERTEX, vertex).put(depthShader, ShaderType.FRAGMENT, depthFragment),
                Runnable::run).join().finishCompile();
        if (depthPipeline == null) {
            fail("depth conversion pass failed to compile", null);
        }
    }

    /** Runs the whole chain; final ends up on {@code target}. */
    public static void draw(RenderTarget target, Function<String, Object> values) {
        if (!ready()) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            ensureTargets(device, target.width, target.height);

            // 1. Clears, for buffers the pack marks cleared.
            for (Map.Entry<Integer, GpuTexture[]> e : halves.entrySet()) {
                RenderTargetPlan.Target t = plan.target(e.getKey());
                if (t.clear()) {
                    encoder.clearColorTexture(e.getValue()[current.get(e.getKey())], clearColor(t));
                }
            }
            // 2. Stand-ins: vanilla's depth, and vanilla's image as colortex0.
            encoder.copyTextureToTexture(target.getDepthTexture(), depthCopy, 0, 0, 0, 0, 0, width, height);
            try (RenderPass convert = encoder.createRenderPass(() -> "Luxium depth to OpenGL convention", depthConvertedView, Optional.empty())) {
                convert.setPipeline(depthPipeline);
                convert.setUniform("reversedDepth", depthCopyView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                convert.draw(3, 1, 0, 0);
            }
            GpuTexture worldCopy = worldCopy(device, encoder, target);
            try (RenderPass seed = encoder.createRenderPass(() -> "Luxium seed colortex0", view(0, true), Optional.empty())) {
                seed.setPipeline(seedPipeline);
                seed.setUniform("world", worldCopyView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                seed.draw(3, 1, 0, 0);
            }

            // 3. The passes.
            String stopAfter = System.getenv("LUXIUM_STOP_AFTER");
            if ("seed".equals(stopAfter)) {
                showDebug(device, encoder, target);
                return;
            }
            for (Pass pass : passes) {
                if (stopAfter != null && pass.name().equals("final")) {
                    showDebug(device, encoder, target);
                    break;
                }
                Map<String, GpuTextureView> inputs = new HashMap<>();
                for (ProgramTranslator.Sampler s : pass.program().samplers()) {
                    inputs.put(s.name(), input(device, encoder, s.name()));
                }
                if (pass.ubo() != null) {
                    try (GpuBufferSlice.MappedView v = pass.ubo().currentBuffer().map(false, true)) {
                        UniformWriter.write(pass.layout(), values, pass.defaults(), v.data());
                    }
                }
                RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(() -> "Luxium " + pass.name());
                if (pass.name().equals("final")) {
                    d.withColorAttachment(target.getColorTextureView());
                } else {
                    for (int index : pass.writes()) {
                        d.withColorAttachment(view(index, false));
                    }
                }
                if (pass.writes().isEmpty() && !pass.name().equals("final")) {
                    continue;   // a pass that writes nowhere (no DRAWBUFFERS): Vitrail leaves it empty too
                }
                RenderPassDescriptor descriptor = d.withRenderArea(new RenderPass.RenderArea(0, 0,
                        pass.name().equals("final") ? target.width : width, pass.name().equals("final") ? target.height : height)).build();
                try (RenderPass rp = encoder.createRenderPass(descriptor)) {
                    rp.setPipeline(pass.pipeline());
                    if (pass.ubo() != null) {
                        rp.setUniform(ProgramTranslator.UNIFORM_BLOCK, pass.ubo().currentBuffer());
                    }
                    var samplers = RenderSystem.getSamplerCache();
                    for (ProgramTranslator.Sampler s : pass.program().samplers()) {
                        boolean nearest = s.name().startsWith("depthtex") || s.name().startsWith("shadowtex");
                        rp.setUniform(s.name(), inputs.get(s.name()), samplers.getClampToEdge(nearest ? FilterMode.NEAREST : FilterMode.LINEAR));
                    }
                    rp.draw(3, 1, 0, 0);
                }
                if (pass.ubo() != null) {
                    pass.ubo().rotate();
                }
                for (int index : new LinkedHashSet<>(pass.writes())) {
                    current.put(index, 1 - current.get(index));   // the flip
                }
                if (pass.name().equals(stopAfter)) {
                    showDebug(device, encoder, target);
                    break;
                }
            }
        } catch (RuntimeException e) {
            fail("drawing threw", e);
        }
    }

    // ---- Debug view: LUXIUM_STOP_AFTER=<pass> LUXIUM_SHOW=<colortexN|depthtex0> --------------

    private static CompiledRenderPipeline showPipeline;

    /**
     * Shows one buffer on screen instead of running the rest of the chain. For finding which pass
     * goes wrong: negative values show as their absolute value, and NaN or infinity as magenta.
     */
    private static void showDebug(GpuDevice device, CommandEncoder encoder, RenderTarget target) {
        String show = System.getenv().getOrDefault("LUXIUM_SHOW", "colortex0");
        if (showPipeline == null) {
            Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/show");
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
                    uniform sampler2D shown;
                    layout(location = 0) in vec2 uv;
                    layout(location = 0) out vec4 color;
                    void main() {
                        vec4 c = texture(shown, uv);
                        bool bad = any(isnan(c)) || any(isinf(c));
                        color = bad ? vec4(1.0, 0.0, 1.0, 1.0) : vec4(abs(c.rgb), 1.0);
                    }
                    """;
            showPipeline = device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/show"))
                            .withVertexShader(shader).withFragmentShader(shader)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayout.builder().withUniform("shown", UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(ColorTargetState.DEFAULT).build(),
                    new LuxiumShaderSource().put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment),
                    Runnable::run).join().finishCompile();
        }
        GpuTextureView view = input(device, encoder, show);
        try (RenderPass rp = encoder.createRenderPass(() -> "Luxium debug show " + show, target.getColorTextureView(), Optional.empty())) {
            rp.setPipeline(showPipeline);
            rp.setUniform("shown", view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            rp.draw(3, 1, 0, 0);
        }
    }

    private static GpuTexture worldCopyTex;
    private static GpuTextureView worldCopyView;

    private static GpuTexture worldCopy(GpuDevice device, CommandEncoder encoder, RenderTarget target) {
        if (worldCopyTex == null || worldCopyTex.getWidth(0) != target.width || worldCopyTex.getHeight(0) != target.height) {
            if (worldCopyTex != null) {
                worldCopyView.close();
                worldCopyTex.close();
            }
            worldCopyTex = device.createTexture("Luxium world copy", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, target.width, target.height, 1, 1);
            worldCopyView = device.createTextureView(worldCopyTex);
        }
        encoder.copyTextureToTexture(target.getColorTexture(), worldCopyTex, 0, 0, 0, 0, 0, target.width, target.height);
        return worldCopyTex;
    }

    /** What a sampler name reads. */
    private static GpuTextureView input(GpuDevice device, CommandEncoder encoder, String name) {
        Integer index = targetIndex(name);
        if (index != null && halves.containsKey(index)) {
            return view(index, true);
        }
        if (name.startsWith("depthtex")) {
            return depthConvertedView;
        }
        if (name.equals("noisetex") && noise != null) {
            return noise;
        }
        if (name.startsWith("shadowtex")) {
            return solid(device, encoder, 0xFFFFFFFF);
        }
        return solid(device, encoder, 0xFF000000);
    }

    static Integer targetIndex(String name) {
        if (name.startsWith("colortex")) {
            try {
                return Integer.parseInt(name.substring("colortex".length()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return LEGACY.get(name);
    }

    /** The current half (read) or the other half (write) of a buffer. */
    private static GpuTextureView view(int index, boolean read) {
        int c = current.get(index);
        return halfViews.get(index)[read ? c : 1 - c];
    }

    /** Engine default clear colors, as Iris: colortex0 the fog color (sky here), colortex1 white, others 0. */
    private static Vector4f clearColor(RenderTargetPlan.Target t) {
        if (t.clearColor() != null) {
            float[] c = t.clearColor();
            return new Vector4f(c[0], c[1], c[2], c[3]);
        }
        float alpha = t.promoted() ? 1f : 0f;
        return switch (t.index()) {
            case 1 -> new Vector4f(1, 1, 1, 1);
            default -> new Vector4f(0, 0, 0, alpha);
        };
    }

    private static void ensureTargets(GpuDevice device, int w, int h) {
        if (w == width && h == height && !halves.isEmpty()) {
            return;
        }
        halfViews.values().forEach(v -> {
            v[0].close();
            v[1].close();
        });
        halves.values().forEach(t -> {
            t[0].close();
            t[1].close();
        });
        halves.clear();
        halfViews.clear();
        width = w;
        height = h;
        Set<Integer> used = new TreeSet<>(plan.declared().keySet());
        for (Pass p : passes) {
            used.addAll(p.writes());
            p.program().samplers().forEach(s -> {
                Integer i = targetIndex(s.name());
                if (i != null) {
                    used.add(i);
                }
            });
        }
        used.add(0);
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        for (int i : used) {
            RenderTargetPlan.Target t = plan.target(i);
            GpuTexture a = device.createTexture("Luxium colortex" + i + " A", usage, t.format(), w, h, 1, 1);
            GpuTexture b = device.createTexture("Luxium colortex" + i + " B", usage, t.format(), w, h, 1, 1);
            halves.put(i, new GpuTexture[]{a, b});
            halfViews.put(i, new GpuTextureView[]{device.createTextureView(a), device.createTextureView(b)});
            current.putIfAbsent(i, 0);
            CommandEncoder e = device.createCommandEncoder();
            e.clearColorTexture(a, clearColor(t));
            e.clearColorTexture(b, clearColor(t));
        }
        if (depthCopy != null) {
            depthCopyView.close();
            depthCopy.close();
        }
        depthCopy = device.createTexture("Luxium depth copy", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.D32_FLOAT, w, h, 1, 1);
        depthCopyView = device.createTextureView(depthCopy);
        if (depthConverted != null) {
            depthConvertedView.close();
            depthConverted.close();
        }
        depthConverted = device.createTexture("Luxium depthtex (OpenGL convention)",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.R32_FLOAT, w, h, 1, 1);
        depthConvertedView = device.createTextureView(depthConverted);
        MetalShadersClient.LOGGER.info("Chain buffers allocated at {}x{}: colortex{}", w, h, used);
    }

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
            MetalShadersClient.LOGGER.error("Pack chain disabled: {}", why);
        } else {
            MetalShadersClient.LOGGER.error("Pack chain disabled: {}", why, e);
        }
    }
}
