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
import com.mojang.renderpearl.api.pipeline.BlendFunction;
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
    /** Depth each pack gbuffer pixel was drawn at; 2.0 where the pack drew nothing (Vitrail's coverage mask). */
    private static GpuTexture coverage;
    private static GpuTextureView coverageView;
    public static final float COVERAGE_NOTHING = 2.0f;
    private static GpuTexture depthConverted;
    private static GpuTextureView depthConvertedView;
    /** depthtex1/2: the depth before translucents, converted (a copy of depthConverted at "after opaque"). */
    private static GpuTexture depthPre;
    private static GpuTextureView depthPreView;
    private static boolean failed;
    private static int width;
    private static int height;

    private PackChain() {
    }

    public static boolean ready() {
        return !failed && !passes.isEmpty();
    }

    /** The pack's {@code clouds=off} (shaders.properties): the game's clouds aren't drawn (Iris ShaderProperties). */
    private static boolean cloudsOff;

    public static void setCloudsOff(boolean off) {
        cloudsOff = off;
    }

    public static boolean cloudsOff() {
        return cloudsOff && ready();
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
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("world", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("worldDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("coverage", UniformType.COMBINED_IMAGE_SAMPLER).build())
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
        // The game's image goes into colortex0 except where the pack's own geometry is the front-most
        // thing: there the coverage mask holds the depth the pack drew at, and the world's depth is
        // the same (within a few units in the last place: on Apple GPUs the two differ by one on
        // sloped surfaces). Positive floats keep their order as integers, hence floatBitsToInt.
        // Reversed-Z: the game drawing in front means a larger depth than the mask.
        String fragment = """
                #version 450
                uniform sampler2D world;
                uniform sampler2D worldDepth;
                uniform sampler2D coverage;
                layout(location = 0) in vec2 uv;
                layout(location = 0) out vec4 color;
                void main() {
                    float mask = texture(coverage, uv).r;
                    float depth = texture(worldDepth, uv).r;
                    bool packOwnsPixel = mask <= 1.5 && abs(floatBitsToInt(depth) - floatBitsToInt(mask)) <= 4;
                    if (packOwnsPixel) {
                        discard;
                    }
                    color = texture(world, uv);
                }
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

    /**
     * Start of a frame, before the world is drawn: clears the buffers the pack marks cleared and
     * the coverage mask, so pack programs drawn during the world (6d) land on clean buffers.
     */
    public static void beginFrame(RenderTarget target) {
        if (!ready()) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            ensureTargets(device, target.width, target.height);
            CommandEncoder encoder = device.createCommandEncoder();
            for (Map.Entry<Integer, GpuTexture[]> e : halves.entrySet()) {
                RenderTargetPlan.Target t = plan.target(e.getKey());
                if (t.clear()) {
                    encoder.clearColorTexture(e.getValue()[current.get(e.getKey())], clearColor(t));
                }
            }
            // When the pack draws the sky, every pixel the game draws nothing in front of is the
            // pack's: the sky stands at the far plane, which is reversed-Z 0.
            encoder.clearColorTexture(coverage, new Vector4f(SkyTakeover.active() ? 0.0f : COVERAGE_NOTHING));
            SkyTakeover.beginFrame(device, encoder);
            TerrainTakeover.beginFrame();
            EntityTakeover.beginFrame();
            deferredRan = false;
            stopped = false;
        } catch (RuntimeException e) {
            fail("beginFrame threw", e);
        }
    }

    /** Per frame: whether the seed and deferred passes already ran ("after opaque"), and whether a debug view stopped the chain. */
    private static boolean deferredRan;
    private static boolean stopped;

    /**
     * "After opaque" (Vitrail frame.md, "The chain is cut in three"): the game's opaque world is
     * done, its translucents aren't drawn yet. Converts the depth (this copy is depthtex1/2, the
     * depth before translucents, and depthtex0 for now), seeds colortex0 and runs the deferred
     * passes, so the pack's water is drawn onto the halves they wrote.
     */
    public static void drawDeferred(RenderTarget target, Function<String, Object> values) {
        if (!ready() || deferredRan) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            ensureTargets(device, target.width, target.height);
            convertDepth(encoder, target, depthConvertedView);
            encoder.copyTextureToTexture(depthConverted, depthPre, 0, 0, 0, 0, 0, width, height);
            seed(device, encoder, target);
            deferredRan = true;
            runPasses(device, encoder, target, values, true);
        } catch (RuntimeException e) {
            fail("drawing the deferred passes threw", e);
        }
    }

    /** Set at "after level", cleared when the composites ran: they wait for the hand. */
    private static boolean compositesPending;

    /**
     * "After level", before the game's 3D HUD (which clears the depth): depthtex0 now includes
     * the translucents. If the deferred part didn't run this frame (no split point was reached,
     * e.g. with improved transparency), the seed and deferred passes run here as before. The
     * composites wait for {@link #afterHand}, so the pack's hand is in the picture they read.
     */
    public static void afterLevel(RenderTarget target, Function<String, Object> values) {
        if (!ready()) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            ensureTargets(device, target.width, target.height);
            if (!deferredRan) {
                convertDepth(encoder, target, depthConvertedView);
                encoder.copyTextureToTexture(depthConverted, depthPre, 0, 0, 0, 0, 0, width, height);
                seed(device, encoder, target);
                runPasses(device, encoder, target, values, true);
            } else {
                convertDepth(encoder, target, depthConvertedView);   // depthtex0 now includes translucents
            }
            compositesPending = true;
        } catch (RuntimeException e) {
            fail("drawing threw", e);
        }
    }

    /**
     * After the game drew the hand (into {@code handDepth}, cleared to "nothing" first): the hand's
     * depth goes into depthtex0/1/2 where it drew (Iris's depth textures include the hand), then
     * the composite passes and final run. Screen effects and the crosshair are drawn after this.
     */
    public static void afterHand(RenderTarget target, GpuTexture handDepth, Function<String, Object> values) {
        if (!ready() || !compositesPending) {
            return;
        }
        compositesPending = false;
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            if (handDepth != null && handDepth.getWidth(0) == width && handDepth.getHeight(0) == height) {
                encoder.copyTextureToTexture(handDepth, depthCopy, 0, 0, 0, 0, 0, width, height);
                mergeHand(device, encoder, depthConverted, depthConvertedView);
                mergeHand(device, encoder, depthPre, depthPreView);
            }
            runPasses(device, encoder, target, values, false);
        } catch (RuntimeException e) {
            fail("drawing the composites threw", e);
        }
    }

    /** The whole after-level part at once (when no hand hook ran this frame). */
    public static void draw(RenderTarget target, Function<String, Object> values) {
        afterLevel(target, values);
        afterHand(target, null, values);
    }

    private static CompiledRenderPipeline mergePipeline;
    private static GpuTexture mergeScratch;
    private static GpuTextureView mergeScratchView;

    /** {@code into} = 1 - hand depth where the hand drew (reversed-Z: anything above 0), else as it was. */
    private static void mergeHand(GpuDevice device, CommandEncoder encoder, GpuTexture into, GpuTextureView intoView) {
        if (mergePipeline == null) {
            Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/merge_hand_depth");
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
                    uniform sampler2D previous;
                    uniform sampler2D handDepth;
                    layout(location = 0) in vec2 uv;
                    layout(location = 0) out vec4 depth;
                    void main() {
                        float h = texture(handDepth, uv).r;
                        depth = vec4(h > 0.0 ? 1.0 - h : texture(previous, uv).r);
                    }
                    """;
            mergePipeline = device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/merge_hand_depth"))
                            .withVertexShader(shader).withFragmentShader(shader)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayout.builder().withUniform("previous", UniformType.COMBINED_IMAGE_SAMPLER)
                                    .withUniform("handDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                            .build(),
                    new LuxiumShaderSource().put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment),
                    Runnable::run).join().finishCompile();
        }
        if (mergeScratch == null || mergeScratch.getWidth(0) != width || mergeScratch.getHeight(0) != height) {
            if (mergeScratch != null) {
                mergeScratchView.close();
                mergeScratch.close();
            }
            mergeScratch = device.createTexture("Luxium hand depth merge", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.R32_FLOAT, width, height, 1, 1);
            mergeScratchView = device.createTextureView(mergeScratch);
        }
        try (RenderPass rp = encoder.createRenderPass(() -> "Luxium merge hand depth", mergeScratchView, Optional.empty())) {
            rp.setPipeline(mergePipeline);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            rp.setUniform("previous", intoView, nearest);
            rp.setUniform("handDepth", depthCopyView, nearest);
            rp.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToTexture(mergeScratch, into, 0, 0, 0, 0, 0, width, height);
    }

    /** The game's depth, copied and converted to the OpenGL convention into {@code into}. */
    private static void convertDepth(CommandEncoder encoder, RenderTarget target, GpuTextureView into) {
        encoder.copyTextureToTexture(target.getDepthTexture(), depthCopy, 0, 0, 0, 0, 0, width, height);
        probeDepth(RenderSystem.getDevice(), encoder, target);
        try (RenderPass convert = encoder.createRenderPass(() -> "Luxium depth to OpenGL convention", into, Optional.empty())) {
            convert.setPipeline(depthPipeline);
            convert.setUniform("reversedDepth", depthCopyView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            convert.draw(3, 1, 0, 0);
        }
    }

    /** Brings the game's image into colortex0 where the pack's own geometry isn't the front-most thing. */
    private static void seed(GpuDevice device, CommandEncoder encoder, RenderTarget target) {
        worldCopy(device, encoder, target);
        try (RenderPass seed = encoder.createRenderPass(() -> "Luxium seed colortex0", view(0, true), Optional.empty())) {
            seed.setPipeline(seedPipeline);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            seed.setUniform("world", worldCopyView, nearest);
            seed.setUniform("worldDepth", depthCopyView, nearest);
            seed.setUniform("coverage", coverageView, nearest);
            seed.draw(3, 1, 0, 0);
        }
        if ("seed".equals(System.getenv("LUXIUM_STOP_AFTER"))) {
            showDebug(device, encoder, target);
            stopped = true;
        }
    }

    /** The deferred passes ({@code deferred == true}) or the composite passes and final. */
    private static void runPasses(GpuDevice device, CommandEncoder encoder, RenderTarget target,
                                  Function<String, Object> values, boolean deferred) {
        String stopAfter = System.getenv("LUXIUM_STOP_AFTER");
        for (Pass pass : passes) {
            if (stopped) {
                return;
            }
            if (pass.name().startsWith("deferred") != deferred) {
                continue;
            }
            if (stopAfter != null && pass.name().equals("final")) {
                showDebug(device, encoder, target);
                stopped = true;
                return;
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
                stopped = true;
                return;
            }
        }
    }

    // ---- The catch: the game's own translucent phase, composed onto colortex0 -----------------

    private static GpuTexture catchTex;
    private static GpuTextureView catchView;
    private static CompiledRenderPipeline composePipeline;

    /**
     * An image cleared to transparent, for the game's translucent phase (translucent entities,
     * particles, clouds, weather) drawn after the seed: what it catches is composed onto colortex0
     * with {@link #composeCatch}. Vitrail does the same for that phase (frame.md, "Where the seed
     * does not reach"). Call outside any render pass.
     */
    public static GpuTextureView catchTarget(RenderTarget target) {
        if (!ready() || !deferredRan || stopped) {
            return null;
        }
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture color = target.getColorTexture();
        if (catchTex == null || catchTex.getWidth(0) != target.width || catchTex.getHeight(0) != target.height
                || catchTex.getFormat() != color.getFormat()) {
            if (catchTex != null) {
                catchView.close();
                catchTex.close();
            }
            catchTex = device.createTexture("Luxium translucent catch",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    color.getFormat(), target.width, target.height, 1, 1);
            catchView = device.createTextureView(catchTex);
        }
        device.createCommandEncoder().clearColorTexture(catchTex, new Vector4f(0, 0, 0, 0));
        return catchView;
    }

    /**
     * Composes the caught image over colortex0. The game's translucent blending
     * (BlendFunction.TRANSLUCENT) onto a transparent image leaves colour already multiplied by
     * its coverage, so the composition is premultiplied "over".
     */
    public static void composeCatch() {
        if (!ready() || catchView == null || stopped) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (composePipeline == null) {
            Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/compose_catch");
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
                    uniform sampler2D caught;
                    layout(location = 0) in vec2 uv;
                    layout(location = 0) out vec4 color;
                    void main() { color = texture(caught, uv); }
                    """;
            composePipeline = device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/compose_catch"))
                            .withVertexShader(shader).withFragmentShader(shader)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayout.builder().withUniform("caught", UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
                                    plan.target(0).format(), plan.target(0).promoted() ? ColorTargetState.WRITE_COLOR : ColorTargetState.WRITE_ALL))
                            .build(),
                    new LuxiumShaderSource().put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment),
                    Runnable::run).join().finishCompile();
            if (composePipeline == null) {
                fail("translucent catch composition failed to compile", null);
                return;
            }
        }
        try (RenderPass rp = device.createCommandEncoder().createRenderPass(() -> "Luxium compose translucent catch", view(0, true), Optional.empty())) {
            rp.setPipeline(composePipeline);
            rp.setUniform("caught", catchView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            rp.draw(3, 1, 0, 0);
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
                        color = bad ? vec4(1.0, 0.0, 1.0, 1.0) : vec4(abs(c.rgb) * SCALE, 1.0);
                    }
                    """;
            // LUXIUM_SHOW_SCALE multiplies what is shown, to make tiny values (raw depth) visible.
            fragment = fragment.replace("SCALE", System.getenv().getOrDefault("LUXIUM_SHOW_SCALE", "1.0"));
            showPipeline = device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/show"))
                            .withVertexShader(shader).withFragmentShader(shader)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayout.builder().withUniform("shown", UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(ColorTargetState.DEFAULT).build(),
                    new LuxiumShaderSource().put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment),
                    Runnable::run).join().finishCompile();
        }
        if (show.equals("depthdiff")) {
            showDepthDiff(device, encoder, target);
            return;
        }
        // "<name>:alt" shows the other half of a buffer (after a pass that wrote it: what it read).
        boolean alt = show.endsWith(":alt");
        String name = alt ? show.substring(0, show.length() - 4) : show;
        Integer altIndex = alt ? targetIndex(name) : null;
        GpuTextureView view = altIndex != null && halves.containsKey(altIndex) ? view(altIndex, false)
                : show.equals("rawdepth") ? depthCopyView
                : show.equals("maindepth") ? target.getDepthTextureView() : input(device, encoder, name);
        try (RenderPass rp = encoder.createRenderPass(() -> "Luxium debug show " + show, target.getColorTextureView(), Optional.empty())) {
            rp.setPipeline(showPipeline);
            rp.setUniform("shown", view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            rp.draw(3, 1, 0, 0);
        }
    }

    private static CompiledRenderPipeline diffPipeline;

    /** Debug (LUXIUM_SHOW=depthdiff): |depthtex0 - depthtex1| times LUXIUM_SHOW_SCALE (default 1000), red. */
    private static void showDepthDiff(GpuDevice device, CommandEncoder encoder, RenderTarget target) {
        if (diffPipeline == null) {
            Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/show_depthdiff");
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
                    uniform sampler2D depth0;
                    uniform sampler2D depth1;
                    layout(location = 0) in vec2 uv;
                    layout(location = 0) out vec4 color;
                    void main() {
                        float d = abs(texture(depth0, uv).r - texture(depth1, uv).r) * SCALE;
                        color = vec4(d, texture(depth0, uv).r < 1.0 ? 0.15 : 0.0, 0.0, 1.0);
                    }
                    """.replace("SCALE", System.getenv().getOrDefault("LUXIUM_SHOW_SCALE", "1000.0"));
            diffPipeline = device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/show_depthdiff"))
                            .withVertexShader(shader).withFragmentShader(shader)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayout.builder().withUniform("depth0", UniformType.COMBINED_IMAGE_SAMPLER)
                                    .withUniform("depth1", UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(ColorTargetState.DEFAULT).build(),
                    new LuxiumShaderSource().put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment),
                    Runnable::run).join().finishCompile();
        }
        try (RenderPass rp = encoder.createRenderPass(() -> "Luxium debug depthdiff", target.getColorTextureView(), Optional.empty())) {
            rp.setPipeline(diffPipeline);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            rp.setUniform("depth0", depthConvertedView, nearest);
            rp.setUniform("depth1", depthPreView, nearest);
            rp.draw(3, 1, 0, 0);
        }
    }

    private static long lastProbe;

    /** Debug (LUXIUM_DEPTH_PROBE=1): reads the game's depth back to the CPU every 5 s and logs its range. */
    private static void probeDepth(GpuDevice device, CommandEncoder encoder, RenderTarget target) {
        if (System.getenv("LUXIUM_DEPTH_PROBE") == null || System.nanoTime() - lastProbe < 5_000_000_000L) {
            return;
        }
        lastProbe = System.nanoTime();
        int w = target.width;
        int h = target.height;
        GpuBuffer buf = device.createBuffer(() -> "Luxium depth probe", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) w * h * 4);
        encoder.copyTextureToBuffer(target.getDepthTexture(), buf, 0, () -> {
            try (GpuBufferSlice.MappedView v = buf.map(true, false)) {
                java.nio.FloatBuffer f = v.data().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
                int zeros = 0;
                for (int i = 0; i < f.limit(); i++) {
                    float d = f.get(i);
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                    if (d == 0f) {
                        zeros++;
                    }
                }
                MetalShadersClient.LOGGER.info("Depth probe (game depth, CPU readback): min {} max {}, {}% exactly 0",
                        min, max, String.format("%.1f", 100.0 * zeros / f.limit()));
            } finally {
                buf.close();
            }
        }, 0);
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

    // ---- For pack programs drawn during the world (6d) ----------------------------------------

    /** The half a geometry program draws into (the current one: geometry passes don't flip). */
    public static GpuTextureView gbufferTarget(int index) {
        return halfViews.containsKey(index) ? view(index, true) : null;
    }

    /** Buffers pack geometry programs write, which must exist even if no full-screen pass names them. */
    private static final Set<Integer> geometryTargets = new TreeSet<>();

    static void requireTargets(java.util.Collection<Integer> indices) {
        if (geometryTargets.addAll(indices)) {
            width = -1;   // reallocate at the next frame with these included
        }
    }

    /** The coverage mask, as an attachment for pack geometry passes. */
    static GpuTextureView coverageTarget() {
        return coverageView;
    }

    public static GpuFormat targetFormat(int index) {
        return plan.target(index).format();
    }

    public static boolean promoted(int index) {
        return plan.target(index).promoted();
    }

    /** What a sampler name reads. Creates placeholders, so call it outside any render pass. */
    public static GpuTextureView inputView(String name) {
        GpuDevice device = RenderSystem.getDevice();
        return input(device, device.createCommandEncoder(), name);
    }

    /** What a sampler name reads. */
    private static GpuTextureView input(GpuDevice device, CommandEncoder encoder, String name) {
        Integer index = targetIndex(name);
        if (index != null && halves.containsKey(index)) {
            return view(index, true);
        }
        if (name.equals("depthtex1") || name.equals("depthtex2")) {
            return depthPreView;
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
        used.addAll(geometryTargets);
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
        depthConverted = device.createTexture("Luxium depthtex0 (OpenGL convention)",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                GpuFormat.R32_FLOAT, w, h, 1, 1);
        depthConvertedView = device.createTextureView(depthConverted);
        if (depthPre != null) {
            depthPreView.close();
            depthPre.close();
        }
        depthPre = device.createTexture("Luxium depthtex1/2 (before translucents)",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R32_FLOAT, w, h, 1, 1);
        depthPreView = device.createTextureView(depthPre);
        if (coverage != null) {
            coverageView.close();
            coverage.close();
        }
        coverage = device.createTexture("Luxium coverage mask",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                GpuFormat.R32_FLOAT, w, h, 1, 1);
        coverageView = device.createTextureView(coverage);
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
