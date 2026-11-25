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
import com.mojang.renderpearl.api.textures.GpuSampler;
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
                        List<Integer> writes, float scale, Set<Integer> mipmapped) {
    }

    /**
     * The buffers a program samples through a mip chain: {@code const bool colortexNMipmapEnabled = true;}
     * or the legacy name (gcolor, gdepth, ..., gaux4), in the preprocessed fragment source, as Iris's
     * ProgramDirectives reads them (see NOTICE).
     */
    static Set<Integer> mipmappedBuffers(String fragmentSource) {
        Set<Integer> out = new TreeSet<>();
        java.util.regex.Matcher m = MIPMAP_DIRECTIVE.matcher(fragmentSource);
        while (m.find()) {
            Integer index = targetIndex(m.group(1));
            if (index != null) {
                if (m.group(2).equals("true")) {
                    out.add(index);
                } else {
                    out.remove(index);
                }
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern MIPMAP_DIRECTIVE = java.util.regex.Pattern.compile(
            "\\bconst\\s+bool\\s+(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])MipmapEnabled\\s*=\\s*(true|false)\\s*;");

    /**
     * luxium.fullSizeBuffers=true (system property, read every frame, for the quick A/B): ignore
     * size.buffer.*; the buffers are rebuilt when it flips.
     */
    private static boolean fullSizeBuffers() {
        return Boolean.getBoolean("luxium.fullSizeBuffers");
    }

    private static boolean builtFullSize;

    private static int targetWidth(int index) {
        return targetSize(index, width, height)[0];
    }

    private static int targetHeight(int index) {
        return targetSize(index, width, height)[1];
    }

    private static final Map<String, Integer> LEGACY = Map.of(
            "gcolor", 0, "gdepth", 1, "gnormal", 2, "composite", 3, "gaux1", 4, "gaux2", 5, "gaux3", 6, "gaux4", 7);

    private static RenderTargetPlan plan;
    private static final List<Pass> passes = new ArrayList<>();
    private static final Map<Integer, GpuTexture[]> halves = new HashMap<>();
    /** Each half's level-0 view: what passes write into and ordinary reads sample. */
    private static final Map<Integer, GpuTextureView[]> halfViews = new HashMap<>();
    /**
     * For buffers some pass mip-samples: a separate texture with the whole mip chain, its view, and
     * single-level views (for the drawn fallback). The pack's buffers stay single-level, so the
     * passes that write them and Metallum's clears touch one level only.
     */
    private static final Map<Integer, GpuTexture> mipTextures = new HashMap<>();
    private static final Map<Integer, GpuTextureView> chainViews = new HashMap<>();
    private static final Map<Integer, GpuTextureView[]> levelViews = new HashMap<>();
    private static final Map<GpuFormat, CompiledRenderPipeline> downsamplePipelines = new HashMap<>();
    private static final Map<Integer, Integer> current = new HashMap<>();
    private static GpuTexture depthCopy;
    private static GpuTextureView depthCopyView;
    private static GpuTexture noiseTexture;
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

    /**
     * The pack's {@code size.buffer.<buffer> = <x> <y>} (shaders.properties), by colortex number:
     * Iris's TextureScaleOverride, where a value with a '.' is a fraction of the screen and one
     * without is a size in pixels (Photon: colortex4 192 108, its clouds and fog at 0.5).
     */
    private static final Map<Integer, String[]> bufferSizes = new HashMap<>();

    public static void setBufferSizes(Map<Integer, String[]> sizes) {
        bufferSizes.clear();
        bufferSizes.putAll(sizes);
        width = -1;
    }

    /**
     * A target's size for a w x h screen. A buffer pack geometry writes keeps the screen's size:
     * it shares the scene's depth attachment. Full-screen passes draw one triangle over their
     * attachments, so a smaller target is simply drawn whole, as Iris sets the viewport to it.
     */
    private static int[] targetSize(int index, int w, int h) {
        String[] size = fullSizeBuffers() ? null : bufferSizes.get(index);
        if (size == null || geometryTargets.contains(index)) {
            return new int[] {w, h};
        }
        return new int[] {Math.max(1, axis(size[0], w)), Math.max(1, axis(size[1], h))};
    }

    private static int axis(String value, int screen) {
        return value.contains(".") ? (int) (screen * Float.parseFloat(value)) : Integer.parseInt(value);
    }

    public static boolean cloudsOff() {
        return cloudsOff && ready();
    }

    /** A pass whose compile was started on the pool (step 7g). */
    private record StartedPass(String name, float scale, ProgramTranslator.Result result, List<Integer> writes,
                               Set<Integer> mipmapped,
                               java.util.concurrent.CompletableFuture<CompiledRenderPipeline.Pending> compile) {
    }

    /** Finishes and closes started compiles that won't be used (the chain failed part way). */
    private static void discard(List<StartedPass> started) {
        for (StartedPass sp : started) {
            try {
                CompiledRenderPipeline c = PipelineCompiler.finish(sp.compile());
                if (c != null) {
                    c.close();
                }
            } catch (RuntimeException ignored) {
                // it failed on its own; the chain's failure is reported already
            }
        }
    }

    /**
     * Translates and compiles every pass. {@code programs} is name -> (vertex, fragment) GLSL,
     * already preprocessed, in run order; final last.
     */
    public static void prepare(RenderTargetPlan targetPlan, List<Map.Entry<String, String[]>> programs,
                               Map<String, Float> scales, NativeImage noiseImage) {
        plan = targetPlan;
        GpuDevice device = RenderSystem.getDevice();
        // Step 7g: every pass's compile is started on PipelineCompiler's pool in this first loop and
        // finished in the second, in run order, so the passes compile side by side.
        List<StartedPass> started = new java.util.ArrayList<>();
        for (Map.Entry<String, String[]> p : programs) {
            String name = p.getKey();
            float scale = scales.getOrDefault(name, 1.0f);
            ProgramTranslator.Result r;
            try {
                // Raw 3D textures of this pass's stage become 2D atlases (VolumeTextures).
                CustomTextures.Stage stage = CustomTextures.stageOfPass(name);
                java.util.function.Function<String, io.github.hecbatista.metalshaders.glsl.VolumeTextures.Volume> volumes =
                        n -> CustomTextures.volume(stage, n);
                r = ProgramTranslator.translate(io.github.hecbatista.metalshaders.glsl.VolumeTextures.rewrite(p.getValue()[0], volumes),
                        io.github.hecbatista.metalshaders.glsl.VolumeTextures.rewrite(p.getValue()[1], volumes),
                        ProgramTranslator.Mode.FULLSCREEN, scale);
            } catch (ProgramTranslator.TranslationException e) {
                discard(started);
                fail(name + ": translation failed: " + e.getMessage(), null);
                return;
            }
            boolean isFinal = name.equals("final");
            // No DRAWBUFFERS / RENDERTARGETS directive: Iris writes buffer 0 (ProgramDirectives; audit S3-03).
            List<Integer> writes = isFinal ? List.of() : r.drawBuffers().isEmpty() ? List.of(0) : r.drawBuffers();
            Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/" + name);
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/pack_" + name))
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
            try {
                started.add(new StartedPass(name, scale, r, writes, mipmappedBuffers(p.getValue()[1]),
                        PipelineCompiler.start(builder.build(), source)));
            } catch (RuntimeException e) {
                discard(started);
                fail(name + ": compile threw", e);
                return;
            }
        }
        // Passes are published only once all of them (and the seed) are built, so a failure part way
        // never leaves a half-built chain reporting ready() (audit S4-02).
        List<Pass> built = new ArrayList<>();
        for (int i = 0; i < started.size(); i++) {
            StartedPass sp = started.get(i);
            String name = sp.name();
            float scale = sp.scale();
            ProgramTranslator.Result r = sp.result();
            List<Integer> writes = sp.writes();
            CompiledRenderPipeline compiled;
            try {
                compiled = PipelineCompiler.finish(sp.compile());
            } catch (RuntimeException e) {
                discard(started.subList(i + 1, started.size()));
                closeBuilt(built);
                fail(name + ": compile threw", e);
                return;
            }
            if (compiled == null) {
                discard(started.subList(i + 1, started.size()));
                closeBuilt(built);
                fail(name + ": the device returned no pipeline (reason logged above)", null);
                return;
            }
            MappableRingBuffer ubo = null;
            try {
                Std140Layout.Layout layout = Std140Layout.of(r.uniforms());
                Map<String, String> defaults = new HashMap<>();
                r.uniforms().forEach(u -> {
                    if (u.defaultValue() != null) {
                        defaults.put(u.name(), u.defaultValue());
                    }
                });
                ubo = layout.size() == 0 ? null
                        : new MappableRingBuffer(() -> "Luxium " + name + " uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, layout.size());
                built.add(new Pass(name, r, layout, defaults, compiled, ubo, writes, scale, sp.mipmapped()));
            } catch (RuntimeException e) {
                compiled.close();
                if (ubo != null) {
                    ubo.close();
                }
                discard(started.subList(i + 1, started.size()));
                closeBuilt(built);
                fail(name + ": setting up its uniforms threw", e);
                return;
            }
            MetalShadersClient.LOGGER.info("Chain pass {}: writes {}, reads {}, scale {}, mipmapped {}", name, writes,
                    r.samplers().stream().map(ProgramTranslator.Sampler::name).toList(), scale, sp.mipmapped());
        }
        GpuTexture madeNoise = null;
        try {
        if (noiseImage != null) {
            GpuTexture t = device.createTexture("Luxium noisetex", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, noiseImage.getWidth(), noiseImage.getHeight(), 1, 1);
            madeNoise = t;
            device.createCommandEncoder().writeToTexture(t, noiseImage);
            noiseTexture = t;
            noise = device.createTextureView(t);
            noiseImage.close();
            // Iris NativeImageBackedNoiseTexture: linear and repeat (packs read it with world coordinates).
            CustomTextures.setSampler(noise, RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
        }
        compileSeed(device);
        } catch (RuntimeException e) {
            if (noiseImage != null) {
                noiseImage.close();   // closing twice is harmless
            }
            if (noiseTexture == null && madeNoise != null) {
                madeNoise.close();
            }
            closeBuilt(built);
            fail("setting up the shared textures and passes threw", e);
            return;
        }
        if (failed) {
            closeBuilt(built);
            return;
        }
        passes.addAll(built);
    }

    private static void closeBuilt(List<Pass> built) {
        for (Pass p : built) {
            try {
                p.pipeline().close();
                if (p.ubo() != null) {
                    p.ubo().close();
                }
            } catch (RuntimeException ignored) {
                // closing a broken pass must not hide the failure being reported
            }
        }
        built.clear();
    }

    /**
     * Forgets the loaded world's programs and frees what depends on them (step 7b: a new world
     * folder means a new set of programs and buffers), here and in every takeover. Called between
     * frames, with no pass open. Metal keeps alive whatever an already-recorded frame still uses
     * (Metallum's command buffers retain their references), so closing now is safe.
     */
    public static void unload() {
        CustomTextures.unload();
        SkyTakeover.unload();
        TerrainTakeover.unload();
        EntityTakeover.unload();
        ShadowTargets.unload();
        for (Pass p : passes) {
            p.pipeline().close();
            if (p.ubo() != null) {
                p.ubo().close();
            }
        }
        passes.clear();
        closeTargets();
        current.clear();
        geometryTargets.clear();
        if (noiseTexture != null) {
            noise.close();
            noiseTexture.close();
        }
        noiseTexture = null;
        noise = null;
        // These depend on colortex0's format, which the next world may change.
        for (CompiledRenderPipeline c : new CompiledRenderPipeline[]{seedPipeline, depthPipeline, depthBothPipeline, composePipeline}) {
            if (c != null) {
                c.close();
            }
        }
        seedPipeline = null;
        depthPipeline = null;
        depthBothPipeline = null;
        composePipeline = null;
        plan = null;
        width = 0;
        height = 0;
        failed = false;
        cloudsOff = false;
        deferredRan = false;
        compositesPending = false;
        stopped = false;
    }

    /** A tiny pass that copies vanilla's image into colortex0, whatever colortex0's format. */
    private static void compileSeed(GpuDevice device) {
        if (failed) {
            return;
        }
        Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/seed");
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/seed"))
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
        Identifier depthShader = Identifier.fromNamespaceAndPath("luxium", "pack/depth_convert");
        String depthFragment = """
                #version 450
                uniform sampler2D reversedDepth;
                layout(location = 0) in vec2 uv;
                layout(location = 0) out vec4 depth;
                void main() { depth = vec4(1.0 - texture(reversedDepth, uv).r); }
                """;
        depthPipeline = device.compilePipeline(RenderPipeline.builder()
                        .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/depth_convert"))
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
        // The same conversion into depthtex0 and depthtex1/2 at once (the deferred split point,
        // where both are the opaque depth): one pass instead of a pass and a copy.
        String depthBothFragment = """
                #version 450
                uniform sampler2D reversedDepth;
                layout(location = 0) in vec2 uv;
                layout(location = 0) out vec4 depth0;
                layout(location = 1) out vec4 depth1;
                void main() { float d = 1.0 - texture(reversedDepth, uv).r; depth0 = vec4(d); depth1 = vec4(d); }
                """;
        Identifier depthBothShader = Identifier.fromNamespaceAndPath("luxium", "pack/depth_convert_both");
        depthBothPipeline = device.compilePipeline(RenderPipeline.builder()
                        .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/depth_convert_both"))
                        .withVertexShader(depthBothShader).withFragmentShader(depthBothShader)
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                        .withBindGroupLayout(BindGroupLayout.builder().withUniform("reversedDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
                        .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                        .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                        .build(),
                new LuxiumShaderSource().put(depthBothShader, ShaderType.VERTEX, vertex).put(depthBothShader, ShaderType.FRAGMENT, depthBothFragment),
                Runnable::run).join().finishCompile();
    }

    private static CompiledRenderPipeline depthBothPipeline;

    /**
     * luxium.oldDepthPath=true (system property, read every frame, for the quick A/B) or
     * LUXIUM_OLD_DEPTH_PATH=1: the depth path before 2026-11-24 (convert then copy; merge the hand
     * per texture through a scratch image and copy back).
     */
    private static boolean oldDepthPath() {
        return OLD_DEPTH_PATH_ENV || Boolean.getBoolean("luxium.oldDepthPath");
    }

    private static final boolean OLD_DEPTH_PATH_ENV = System.getenv("LUXIUM_OLD_DEPTH_PATH") != null;

    /** depthtex0 and depthtex1/2 both = the game's depth, converted (the deferred split point). */
    private static void convertDepthBoth(CommandEncoder encoder, RenderTarget target) {
        if (oldDepthPath() || depthBothPipeline == null) {
            convertDepth(encoder, target, depthConvertedView);
            encoder.copyTextureToTexture(depthConverted, depthPre, 0, 0, 0, 0, 0, width, height);
            return;
        }
        encoder.copyTextureToTexture(target.getDepthTexture(), depthCopy, 0, 0, 0, 0, 0, width, height);
        probeDepth(RenderSystem.getDevice(), encoder, target);
        RenderPassDescriptor d = RenderPassDescriptor.builder(() -> "Luxium depth to OpenGL convention (depthtex0 and 1/2)")
                .withColorAttachment(depthConvertedView).withColorAttachment(depthPreView)
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height)).build();
        try (RenderPass convert = encoder.createRenderPass(d)) {
            convert.setPipeline(depthBothPipeline);
            convert.setUniform("reversedDepth", depthCopyView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            convert.draw(3, 1, 0, 0);
        }
    }

    /**
     * Start of a frame, before the world is drawn: clears the buffers the pack marks cleared and
     * the coverage mask, so pack programs drawn during the world (6d) land on clean buffers.
     */
    public static void beginFrame(RenderTarget target) {
        TerrainTakeover.resetDrawFlags();
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
                    // Both halves, as Iris's ClearPassCreator does: the one a pass writes next frame
                    // must not hold old contents either (audit S3-04).
                    Vector4f color = clearColor(t);
                    encoder.clearColorTexture(e.getValue()[0], color);
                    encoder.clearColorTexture(e.getValue()[1], color);
                }
            }
            // Nothing is the pack's yet. If the pack draws the sky, skyDrawn() marks the whole
            // background as the pack's when the sky pass opens (the game skips the sky in some
            // frames: no sky in the world, a boss's world fog, lava or powder snow, blindness).
            encoder.clearColorTexture(coverage, new Vector4f(COVERAGE_NOTHING));
            SkyTakeover.beginFrame(device, encoder);
            TerrainTakeover.beginFrame();
            EntityTakeover.beginFrame();
            deferredRan = false;
            prepareRan = false;
            stopped = false;
        } catch (RuntimeException e) {
            fail("beginFrame threw", e);
        }
    }

    /**
     * The pack is drawing this frame's sky (from SkyRendererMixin, just before the sky pass
     * opens): every pixel the game draws nothing in front of is the pack's, because the sky stands
     * at the far plane, which is reversed-Z 0.
     */
    public static void skyDrawn(CommandEncoder encoder) {
        if (coverage != null) {
            encoder.clearColorTexture(coverage, new Vector4f(0.0f));
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
        long luxium$t0 = PerfStats.start();
        try {
            drawDeferredTimed(target, values);
        } finally {
            PerfStats.end(PerfStats.Section.DEFERRED, luxium$t0);
        }
    }

    private static void drawDeferredTimed(RenderTarget target, Function<String, Object> values) {
        if (!ready() || deferredRan) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            ensureTargets(device, target.width, target.height);
            convertDepthBoth(encoder, target);
            seed(device, encoder, target);
            deferredRan = true;
            runPasses(device, encoder, target, values, "deferred");
        } catch (RuntimeException e) {
            fail("drawing the deferred passes threw", e);
        }
    }

    private static String groupOf(String pass) {
        return pass.startsWith("prepare") ? "prepare" : pass.startsWith("deferred") ? "deferred" : "composite";
    }

    /** Per frame: whether the prepare passes ran. */
    private static boolean prepareRan;

    /**
     * The pack's prepare passes (prepare, prepare1...), once per frame, before the pack's sky or
     * its opaque terrain, whichever the game draws first (MakeUp's prepare draws the sky colour its
     * sky and terrain read). Iris runs them after the shadow pass; Luxium draws the shadow map with
     * the opaque terrain, so a prepare pass that reads it sees the previous frame's.
     */
    public static void drawPrepare(RenderTarget target, Function<String, Object> values) {
        if (!ready() || prepareRan) {
            return;
        }
        prepareRan = true;
        if (passes.stream().noneMatch(p -> p.name().startsWith("prepare"))) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            ensureTargets(device, target.width, target.height);
            runPasses(device, encoder, target, values, "prepare");
        } catch (RuntimeException e) {
            fail("drawing the prepare passes threw", e);
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
        long luxium$t0 = PerfStats.start();
        try {
            afterLevelTimed(target, values);
        } finally {
            PerfStats.end(PerfStats.Section.COMPOSITES, luxium$t0);
        }
    }

    private static void afterLevelTimed(RenderTarget target, Function<String, Object> values) {
        if (!ready()) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            ensureTargets(device, target.width, target.height);
            if (!deferredRan) {
                convertDepthBoth(encoder, target);
                seed(device, encoder, target);
                runPasses(device, encoder, target, values, "deferred");
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
        long luxium$t0 = PerfStats.start();
        try {
            afterHandTimed(target, handDepth, values);
        } finally {
            PerfStats.end(PerfStats.Section.COMPOSITES, luxium$t0);
        }
    }

    private static void afterHandTimed(RenderTarget target, GpuTexture handDepth, Function<String, Object> values) {
        if (!ready() || !compositesPending) {
            return;
        }
        compositesPending = false;
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            if (handDepth != null && handDepth.getWidth(0) == width && handDepth.getHeight(0) == height) {
                encoder.copyTextureToTexture(handDepth, depthCopy, 0, 0, 0, 0, 0, width, height);
                if (oldDepthPath()) {
                    mergeHand(device, encoder, depthConverted, depthConvertedView);
                    mergeHand(device, encoder, depthPre, depthPreView);
                } else {
                    mergeHandBoth(device, encoder);
                }
            }
            runPasses(device, encoder, target, values, "composite");
            FlashProbe.capture(device, encoder, target.getColorTexture());
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
            Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/merge_hand_depth");
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
                            .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/merge_hand_depth"))
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

    private static CompiledRenderPipeline mergeBothPipeline;
    /** The other half of depthtex0 and of depthtex1/2: the merge writes there, then they swap. */
    private static GpuTexture spareConverted;
    private static GpuTextureView spareConvertedView;
    private static GpuTexture spareDepthPre;
    private static GpuTextureView spareDepthPreView;

    /**
     * The hand's depth into depthtex0 and depthtex1/2 in one pass with two attachments, written into
     * the spare images, which then become depthtex0 and depthtex1/2 (no copy back). Same rule as
     * {@link #mergeHand}: 1 - hand depth where the hand drew, else as it was.
     */
    private static void mergeHandBoth(GpuDevice device, CommandEncoder encoder) {
        if (mergeBothPipeline == null) {
            Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/merge_hand_depth_both");
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
                    uniform sampler2D previous0;
                    uniform sampler2D previous1;
                    uniform sampler2D handDepth;
                    layout(location = 0) in vec2 uv;
                    layout(location = 0) out vec4 depth0;
                    layout(location = 1) out vec4 depth1;
                    void main() {
                        float h = texture(handDepth, uv).r;
                        depth0 = vec4(h > 0.0 ? 1.0 - h : texture(previous0, uv).r);
                        depth1 = vec4(h > 0.0 ? 1.0 - h : texture(previous1, uv).r);
                    }
                    """;
            mergeBothPipeline = device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/merge_hand_depth_both"))
                            .withVertexShader(shader).withFragmentShader(shader)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayout.builder().withUniform("previous0", UniformType.COMBINED_IMAGE_SAMPLER)
                                    .withUniform("previous1", UniformType.COMBINED_IMAGE_SAMPLER)
                                    .withUniform("handDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                            .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                            .build(),
                    new LuxiumShaderSource().put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment),
                    Runnable::run).join().finishCompile();
        }
        if (spareConverted == null || spareConverted.getWidth(0) != width || spareConverted.getHeight(0) != height) {
            closeSpareDepth();
            spareConverted = device.createTexture("Luxium depthtex0 (OpenGL convention) B", DEPTH_IMAGE_USAGE, GpuFormat.R32_FLOAT, width, height, 1, 1);
            spareConvertedView = device.createTextureView(spareConverted);
            spareDepthPre = device.createTexture("Luxium depthtex1/2 (before translucents) B", DEPTH_IMAGE_USAGE, GpuFormat.R32_FLOAT, width, height, 1, 1);
            spareDepthPreView = device.createTextureView(spareDepthPre);
        }
        RenderPassDescriptor d = RenderPassDescriptor.builder(() -> "Luxium merge hand depth (depthtex0 and 1/2)")
                .withColorAttachment(spareConvertedView).withColorAttachment(spareDepthPreView)
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height)).build();
        try (RenderPass rp = encoder.createRenderPass(d)) {
            rp.setPipeline(mergeBothPipeline);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            rp.setUniform("previous0", depthConvertedView, nearest);
            rp.setUniform("previous1", depthPreView, nearest);
            rp.setUniform("handDepth", depthCopyView, nearest);
            rp.draw(3, 1, 0, 0);
        }
        GpuTexture t = depthConverted;
        GpuTextureView v = depthConvertedView;
        depthConverted = spareConverted;
        depthConvertedView = spareConvertedView;
        spareConverted = t;
        spareConvertedView = v;
        t = depthPre;
        v = depthPreView;
        depthPre = spareDepthPre;
        depthPreView = spareDepthPreView;
        spareDepthPre = t;
        spareDepthPreView = v;
    }

    private static void closeSpareDepth() {
        if (spareConverted != null) {
            spareConvertedView.close();
            spareConverted.close();
            spareDepthPreView.close();
            spareDepthPre.close();
        }
        spareConverted = null;
        spareConvertedView = null;
        spareDepthPre = null;
        spareDepthPreView = null;
    }

    /** Both depthtex images and their spares: sampled, drawn into (convert, merge) and copied. */
    private static final int DEPTH_IMAGE_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

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

    /** The passes of one group: "prepare", "deferred", or "composite" (the composites and final). */
    private static void runPasses(GpuDevice device, CommandEncoder encoder, RenderTarget target,
                                  Function<String, Object> values, String group) {
        String stopAfter = System.getenv("LUXIUM_STOP_AFTER");
        // Render targets earlier passes of this stage wrote this frame: their texture.<stage>
        // overrides are off from then on (Iris CompositeRenderer, flippedAtLeastOnce).
        java.util.Set<Integer> writtenThisStage = new java.util.HashSet<>();
        for (Pass pass : passes) {
            if (stopped) {
                return;
            }
            if (!groupOf(pass.name()).equals(group)) {
                continue;
            }
            CustomTextures.Stage stage = CustomTextures.stageOfPass(pass.name());
            if (stopAfter != null && pass.name().equals("final")) {
                showDebug(device, encoder, target);
                stopped = true;
                return;
            }
            // colortexNMipmapEnabled: fill the mip chain of the half this pass reads, as Iris's
            // CompositeRenderer.setupMipmapping does with glGenerateMipmap just before the pass.
            Set<Integer> mipmapped = mipsOff() ? Set.of() : pass.mipmapped();
            for (int index : mipmapped) {
                generateMips(device, encoder, index);
            }
            Map<String, GpuTextureView> inputs = new HashMap<>();
            Map<String, GpuSampler> samplers = new HashMap<>();
            for (ProgramTranslator.Sampler s : pass.program().samplers()) {
                GpuTextureView custom = CustomTextures.volumeView(stage, s.name());
                if (custom == null) {
                    custom = CustomTextures.override(stage, s.name(), writtenThisStage);
                }
                Integer index = custom == null ? targetIndex(s.name()) : null;
                if (index != null && mipmapped.contains(index) && chainViews.containsKey(index)) {
                    inputs.put(s.name(), chainViews.get(index));
                    samplers.put(s.name(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true));
                    continue;
                }
                inputs.put(s.name(), custom != null ? custom : input(device, encoder, s.name(), readsWaterShadow(pass.program().samplers())));
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
            // A scaled pass (scale.<program>) covers only the 0..s corner: its projection maps the
            // full-screen triangle there, but the triangle reaches twice that area, so the render area
            // (a scissor in Metallum) keeps it to the corner, as Iris's viewport does (CompositeRenderer:
            // (int) (viewWidth * scale)). Measured 2026-11-23: Complementary's composite4 (0.4) cost
            // 3x LeadLight's without it. OpenGL's vertical convention holds end to end (step 6f), so
            // the corner is row 0 in Metal's coordinates.
            boolean isFinal = pass.name().equals("final");
            float areaScale = isFinal || System.getenv("LUXIUM_NO_SCALE_SCISSOR") != null || Boolean.getBoolean("luxium.noScaleScissor") ? 1f : pass.scale();
            // The pass's own size: its buffers' (size.buffer.*; a pass's buffers share one size, as
            // the game requires of a render pass's attachments), else the screen's.
            int baseW = isFinal || pass.writes().isEmpty() ? width : targetWidth(pass.writes().getFirst());
            int baseH = isFinal || pass.writes().isEmpty() ? height : targetHeight(pass.writes().getFirst());
            int areaW = isFinal ? target.width : areaScale < 1f ? Math.max(1, (int) (baseW * areaScale)) : baseW;
            int areaH = isFinal ? target.height : areaScale < 1f ? Math.max(1, (int) (baseH * areaScale)) : baseH;
            RenderPassDescriptor descriptor = d.withRenderArea(new RenderPass.RenderArea(0, 0, areaW, areaH)).build();
            try (RenderPass rp = encoder.createRenderPass(descriptor)) {
                rp.setPipeline(pass.pipeline());
                if (pass.ubo() != null) {
                    rp.setUniform(ProgramTranslator.UNIFORM_BLOCK, pass.ubo().currentBuffer());
                }
                for (ProgramTranslator.Sampler s : pass.program().samplers()) {
                    GpuTextureView v = inputs.get(s.name());
                    GpuSampler sampler = samplers.get(s.name());
                    rp.setUniform(s.name(), v, sampler != null ? sampler : CustomTextures.samplerFor(s.name(), v));
                }
                rp.draw(3, 1, 0, 0);
            }
            if (pass.ubo() != null) {
                pass.ubo().rotate();
            }
            for (int index : new LinkedHashSet<>(pass.writes())) {
                current.put(index, 1 - current.get(index));   // the flip
            }
            writtenThisStage.addAll(pass.writes());
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
            Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/compose_catch");
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
                            .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/compose_catch"))
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
            Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/show");
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
                        if (FRAGCOORD) {
                            // Debug (LUXIUM_SHOW=fragcoord): green = gl_FragCoord.y / height, red = uv.y.
                            // OpenGL's convention has both grow upward, so they should match.
                            color = vec4(uv.y, gl_FragCoord.y / float(textureSize(shown, 0).y), 0.0, 1.0);
                        }
                    }
                    """;
            // LUXIUM_SHOW_SCALE multiplies what is shown, to make tiny values (raw depth) visible.
            fragment = fragment.replace("SCALE", System.getenv().getOrDefault("LUXIUM_SHOW_SCALE", "1.0"))
                    .replace("FRAGCOORD", show.equals("fragcoord") ? "true" : "false");
            showPipeline = device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/show"))
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
        if (show.equals("fragcoord")) {
            name = "colortex0";
        }
        GpuTextureView view = altIndex != null && halves.containsKey(altIndex) ? view(altIndex, false)
                : show.equals("rawdepth") ? depthCopyView
                : show.equals("maindepth") ? target.getDepthTextureView() : input(device, encoder, name, false);
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
            Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/show_depthdiff");
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
                            .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/show_depthdiff"))
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
        try {
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
        } catch (RuntimeException e) {
            buf.close();   // the callback that would have closed it was never registered (audit S4-09)
            throw e;
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

    /** Whether a program declares watershadow, which changes what its {@code shadow} reads. */
    public static boolean readsWaterShadow(List<ProgramTranslator.Sampler> samplers) {
        return samplers.stream().anyMatch(s -> s.name().equals("watershadow"));
    }

    /**
     * What a sampler name reads. Creates placeholders, so call it outside any render pass.
     *
     * @param programSamplers every sampler of the program that reads it
     */
    /** A gbuffers or shadow stage with its raw 3D textures as atlases (VolumeTextures, GBUFFERS stage). */
    public static String gbufferVolumes(String source) {
        return io.github.hecbatista.metalshaders.glsl.VolumeTextures.rewrite(source,
                n -> CustomTextures.volume(CustomTextures.Stage.GBUFFERS, n));
    }

    public static GpuTextureView inputView(String name, List<ProgramTranslator.Sampler> programSamplers) {
        // Every caller is a gbuffers or shadow program: Iris's GBUFFERS_AND_SHADOW texture stage.
        GpuTextureView custom = CustomTextures.volumeView(CustomTextures.Stage.GBUFFERS, name);
        if (custom == null) {
            custom = CustomTextures.override(CustomTextures.Stage.GBUFFERS, name, java.util.Set.of());
        }
        if (custom != null) {
            return custom;
        }
        GpuDevice device = RenderSystem.getDevice();
        return input(device, device.createCommandEncoder(), name, readsWaterShadow(programSamplers));
    }

    /** What a sampler name reads. */
    private static GpuTextureView input(GpuDevice device, CommandEncoder encoder, String name, boolean waterShadow) {
        GpuTextureView shadow = ShadowTargets.input(name, waterShadow);
        if (shadow != null) {
            return shadow;
        }
        Integer index = targetIndex(name);
        if (index != null && halves.containsKey(index)) {
            return view(index, true);
        }
        // customTexture.<name>: after the render targets, before noisetex and depthtex (Iris's
        // IrisSamplers order in CompositeRenderer and the gbuffers pipelines).
        GpuTextureView custom = CustomTextures.byName(name);
        if (custom != null) {
            return custom;
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
        // The game's lightmap (Iris's "lightmap" sampler). Game draws bind it themselves as
        // Sampler2 (EntityTakeover's renames); terrain and others read it here. Miniature's
        // terrain takes its whole ambient light from it: as the black placeholder, terrain was black.
        if (name.equals("lightmap")) {
            return net.minecraft.client.Minecraft.getInstance().gameRenderer.lightmap();
        }
        if (name.startsWith("shadowtex")) {
            return solid(device, encoder, 0xFFFFFFFF);
        }
        // No PBR resource pack: the atlas's normal and specular maps are Iris's defaults
        // (PBRType: NORMAL 0x7F7FFFFF, a flat normal; SPECULAR 0, both written as RGBA).
        if (name.equals("normals")) {
            return solid(device, encoder, 0xFF7F7FFF);
        }
        if (name.equals("specular")) {
            return solid(device, encoder, 0x00000000);
        }
        return solid(device, encoder, 0xFF000000);
    }

    public static Integer targetIndex(String name) {
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
            // A promoted RGB buffer reads alpha 1 (its writes are color-only), whatever clear alpha was asked (audit S3-04).
            return new Vector4f(c[0], c[1], c[2], t.promoted() ? 1f : c[3]);
        }
        float alpha = t.promoted() ? 1f : 0f;
        return switch (t.index()) {
            // Iris ClearPassCreator: colortex0 is cleared to the fog colour with alpha 1, so where
            // nothing is drawn (below the horizon, past the world's edge) the pack sees the fog.
            case 0 -> {
                float[] fog = io.github.hecbatista.metalshaders.uniform.GameUniforms.fogColor();
                yield new Vector4f(fog[0], fog[1], fog[2], 1f);
            }
            case 1 -> new Vector4f(1, 1, 1, 1);
            default -> new Vector4f(0, 0, 0, alpha);
        };
    }

    private static final boolean NO_MIPMAPS_ENV = System.getenv("LUXIUM_NO_MIPMAPS") != null;

    /**
     * LUXIUM_NO_MIPMAPS=1, or the system property luxium.noMipmaps=true (read every frame, so the
     * tour's quick A/B can flip it): ignore colortexNMipmapEnabled, as before mip chains existed.
     */
    private static boolean mipsOff() {
        return NO_MIPMAPS_ENV || Boolean.getBoolean("luxium.noMipmaps");
    }

    /**
     * Whether a format can be sampled with linear filtering (and so mip-mapped by averaging): not
     * the integer formats.
     */
    private static boolean filterable(GpuFormat format) {
        String n = format.name();
        return !n.contains("UINT") && !n.contains("SINT");
    }

    /**
     * Brings the half of buffer {@code index} that passes read now into its mip texture (level 0, a
     * blit copy) and fills levels 1..n with Metal's blit generateMipmaps ({@link MetalMips}), what
     * glGenerateMipmap does for Iris. If Metallum's blit isn't reachable, each level is drawn from
     * the one above with a bilinear tap at the pixel centre (a 2x2 box), one render pass per level.
     * Measured 2026-11-23 (quick A/B, overlook): drawn levels for colortex0 and colortex5 cost 7% of
     * the frame rate; the blit is one command per texture.
     */
    private static void generateMips(GpuDevice device, CommandEncoder encoder, int index) {
        GpuTexture mip = mipTextures.get(index);
        if (mip == null) {
            return;
        }
        encoder.copyTextureToTexture(halves.get(index)[current.get(index)], mip, 0, 0, 0, 0, 0, targetWidth(index), targetHeight(index));
        if (MetalMips.generate(encoder, mip)) {
            return;
        }
        GpuTextureView[] levels = levelViews.get(index);
        CompiledRenderPipeline pipeline = downsamplePipeline(device, plan.target(index).format());
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        for (int l = 1; l < levels.length; l++) {
            int level = l;
            try (RenderPass rp = encoder.createRenderPass(() -> "Luxium mip colortex" + index + " level " + level, levels[l], Optional.empty())) {
                rp.setPipeline(pipeline);
                rp.setUniform("above", levels[l - 1], linear);
                rp.draw(3, 1, 0, 0);
            }
        }
    }

    private static CompiledRenderPipeline downsamplePipeline(GpuDevice device, GpuFormat format) {
        return downsamplePipelines.computeIfAbsent(format, f -> {
            Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/mip_downsample");
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
                    uniform sampler2D above;
                    layout(location = 0) in vec2 uv;
                    layout(location = 0) out vec4 color;
                    void main() {
                        color = texture(above, uv);
                    }
                    """;
            return device.compilePipeline(RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/mip_downsample_" + f.name().toLowerCase(java.util.Locale.ROOT)))
                            .withVertexShader(shader).withFragmentShader(shader)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayout.builder().withUniform("above", UniformType.COMBINED_IMAGE_SAMPLER).build())
                            .withColorTargetState(new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_ALL))
                            .build(),
                    new LuxiumShaderSource().put(shader, ShaderType.VERTEX, vertex).put(shader, ShaderType.FRAGMENT, fragment),
                    Runnable::run).join().finishCompile();
        });
    }

    /** Closes every chain buffer, its views and its mip views. */
    private static void closeTargets() {
        halfViews.values().forEach(v -> {
            v[0].close();
            v[1].close();
        });
        halfViews.clear();
        chainViews.values().forEach(GpuTextureView::close);
        chainViews.clear();
        levelViews.values().forEach(levels -> {
            for (GpuTextureView l : levels) {
                l.close();
            }
        });
        levelViews.clear();
        mipTextures.values().forEach(GpuTexture::close);
        mipTextures.clear();
        halves.values().forEach(t -> {
            t[0].close();
            t[1].close();
        });
        halves.clear();
    }

    private static void ensureTargets(GpuDevice device, int w, int h) {
        if (w == width && h == height && !halves.isEmpty() && builtFullSize == fullSizeBuffers()) {
            return;
        }
        builtFullSize = fullSizeBuffers();
        closeTargets();
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
        Set<Integer> mipped = new TreeSet<>();
        for (Pass p : passes) {
            mipped.addAll(p.mipmapped());
        }
        for (int i : used) {
            RenderTargetPlan.Target t = plan.target(i);
            int tw = targetWidth(i);
            int th = targetHeight(i);
            // Each half is closed again if a later allocation of this buffer fails (audit S4-06).
            GpuTexture a = null;
            GpuTexture b = null;
            GpuTextureView va = null;
            try {
                a = device.createTexture("Luxium colortex" + i + " A", usage, t.format(), tw, th, 1, 1);
                b = device.createTexture("Luxium colortex" + i + " B", usage, t.format(), tw, th, 1, 1);
                va = device.createTextureView(a);
                GpuTextureView vb = device.createTextureView(b);
                halves.put(i, new GpuTexture[]{a, b});
                halfViews.put(i, new GpuTextureView[]{va, vb});
            } catch (RuntimeException ex) {
                for (AutoCloseable c : new AutoCloseable[]{va, a, b}) {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Exception ignored) {
                            // the allocation failure is the one to report
                        }
                    }
                }
                throw ex;
            }
            if (mipped.contains(i) && filterable(t.format())) {
                int levels = 1 + (31 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(tw, th))));
                GpuTexture mip = device.createTexture("Luxium colortex" + i + " mips", GpuTexture.USAGE_COPY_DST
                        | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, t.format(), tw, th, 1, levels);
                mipTextures.put(i, mip);
                chainViews.put(i, device.createTextureView(mip));
                GpuTextureView[] perLevel = new GpuTextureView[levels];
                for (int l = 0; l < levels; l++) {
                    perLevel[l] = device.createTextureView(mip, l, 1);
                }
                levelViews.put(i, perLevel);
            }
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
        depthConverted = device.createTexture("Luxium depthtex0 (OpenGL convention)", DEPTH_IMAGE_USAGE, GpuFormat.R32_FLOAT, w, h, 1, 1);
        depthConvertedView = device.createTextureView(depthConverted);
        if (depthPre != null) {
            depthPreView.close();
            depthPre.close();
        }
        depthPre = device.createTexture("Luxium depthtex1/2 (before translucents)", DEPTH_IMAGE_USAGE, GpuFormat.R32_FLOAT, w, h, 1, 1);
        closeSpareDepth();
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
        MetalShadersClient.packUnusable("its program " + why);
    }
}
