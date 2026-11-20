package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.glsl.ProgramTranslator;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import io.github.hecbatista.metalshaders.uniform.UniformWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Step 6d-4: the level's entities and block entities drawn by the pack's entity programs, into the
 * pack's targets.
 * <p>
 * Every such draw goes through PreparedRenderType.draw (26.3), called per draw by
 * RenderTypeFeatureRenderer.executeGroup with the pass the level hands it. While a window is open
 * (the level's solid features, then its translucent ones), a draw whose pipeline the window's
 * family serves is handed a pass of the pack's instead (the "hold", kept open across a run of
 * served draws), the level's pass stepping aside ({@link LevelPass#suspend}); a draw it doesn't
 * serve goes back to the level's pass, which closes the hold first. The pipeline is swapped for the
 * pack's program compiled with the vanilla pipeline's state; the game's own draw then binds the
 * per-draw DynamicTransforms block and its textures (Sampler0 skin, Sampler1 overlay), and the
 * pack's block and textures are bound right after the pipeline. This follows Vitrail's notes
 * (docs/internals/entities.md, "The door is a group of draws") and Iris's choice of key: the
 * RenderPipeline.
 * <p>
 * Two families: SOLID (gbuffers_entities, unblended ENTITY-format pipelines, before the deferred
 * cut, writing the coverage mask) and TRANSLUCENT (gbuffers_entities_translucent, blended ones,
 * after the cut, onto the halves the deferred passes wrote, with the vanilla pipeline's blend unless
 * the pack's blend.* says otherwise). Iris's alpha tests: ENTITIES_SOLID off, ENTITIES_CUTOUT and
 * translucent one tenth (vanilla marks cutout pipelines with ALPHA_CUTOUT).
 */
public final class EntityTakeover {
    private record Program(ProgramTranslator.Result result, Std140Layout.Layout layout, Map<String, String> defaults) {
    }

    /** Pack albedo names -> the game's skin sampler; lightmap -> the game's lightmap sampler. */
    private static final Map<String, String> RENAMES = Map.of(
            "tex", "Sampler0", "texture", "Sampler0", "gtexture", "Sampler0", "gcolor", "Sampler0", "lightmap", "Sampler2");
    private static final Set<String> GAME_BOUND = Set.of("Sampler0", "Sampler1", "Sampler2");
    private static final Matrix4f LIGHTMAP_MATRIX = new Matrix4f(
            0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0.03125f, 0.03125f, 0.03125f, 1);
    /** WorldRenderingPhase.ENTITIES (Iris). */
    private static final int STAGE_ENTITIES = 11;
    private static final Set<String> ELEMENTS = Set.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");

    /** One pack program and the vanilla pipelines it serves. */
    private static final class Family {
        final String program;
        final boolean translucent;
        final Map<Float, Program> byAlpha = new HashMap<>();
        final Map<RenderPipeline, CompiledRenderPipeline> compiled = new IdentityHashMap<>();
        final Set<RenderPipeline> refused = new HashSet<>();
        final Map<String, GpuTextureView> inputs = new HashMap<>();
        String[] glsl;
        Properties properties = new Properties();
        List<Integer> writes = List.of();
        boolean prepared;
        MappableRingBuffer ubo;
        int served;

        Family(String program, boolean translucent) {
            this.program = program;
            this.translucent = translucent;
        }

        boolean coverage() {
            return !translucent;   // translucents are drawn after the seed, which is all the mask is for
        }

        Program program(float alpha) throws ProgramTranslator.TranslationException {
            Program p = byAlpha.get(alpha);
            if (p == null) {
                ProgramTranslator.Result r = ProgramTranslator.translateForDraw(glsl[0], glsl[1], ELEMENTS,
                        new ProgramTranslator.Format(alpha, coverage(), RENAMES));
                Map<String, String> defaults = new HashMap<>();
                r.uniforms().forEach(u -> {
                    if (u.defaultValue() != null) {
                        defaults.put(u.name(), u.defaultValue());
                    }
                });
                p = new Program(r, Std140Layout.of(r.uniforms()), defaults);
                byAlpha.put(alpha, p);
            }
            return p;
        }

        float alphaFor(RenderPipeline pipeline) {
            if (translucent) {
                return 0.1f;   // Iris: translucent entities ONE_TENTH_ALPHA
            }
            return pipeline.getShaderDefines().values().containsKey("ALPHA_CUTOUT") ? 0.1f : Float.NaN;
        }
    }

    private static final Family SOLID = new Family("gbuffers_entities", false);
    private static final Family TRANSLUCENT = new Family("gbuffers_entities_translucent", true);
    private static final Map<CompiledRenderPipeline, Program> programOf = new IdentityHashMap<>();
    private static int compiledCount;

    // Per-frame state.
    private static Family window;
    private static RenderPass hold;
    private static boolean inHold;
    private static long logged;

    private EntityTakeover() {
    }

    /** Debug: LUXIUM_NO_ENTITIES=1 leaves entities to the game's shaders, for before/after shots. */
    private static final boolean SWITCHED_OFF = System.getenv("LUXIUM_NO_ENTITIES") != null;

    private static boolean active(Family f) {
        return f.prepared && !SWITCHED_OFF && PackChain.ready();
    }

    /**
     * Translates the pack's entity programs (pipelines are compiled on first use). {@code translucent}
     * may be null: the translucent family then falls back to gbuffers_entities, as Iris's program
     * fallback does (ProgramId.EntitiesTrans -> Entities).
     */
    public static void prepare(String[] solid, String[] translucent, Properties properties) {
        prepareFamily(SOLID, solid, properties);
        prepareFamily(TRANSLUCENT, translucent != null ? translucent : solid, properties);
    }

    private static void prepareFamily(Family f, String[] glsl, Properties properties) {
        try {
            f.glsl = glsl;
            f.properties = properties;
            Program p = f.program(f.translucent ? 0.1f : Float.NaN);
            if (!f.translucent) {
                f.program(0.1f);
            }
            f.writes = p.result().drawBuffers().isEmpty() ? List.of(0) : p.result().drawBuffers();
            PackChain.requireTargets(f.writes);
            f.prepared = true;
            MetalShadersClient.LOGGER.info("Entity takeover ({}): writes colortex{}{}; samplers {}", f.program, f.writes,
                    f.coverage() ? " plus the coverage mask" : "", p.result().samplers().stream().map(ProgramTranslator.Sampler::name).toList());
        } catch (Exception e) {
            MetalShadersClient.LOGGER.error("Entity takeover ({}) disabled: {}", f.program, e.getMessage(), e);
        }
    }

    /** Whether this vanilla pipeline is one the open window's program draws. */
    public static boolean serves(RenderPipeline pipeline) {
        Family f = window;
        if (f == null || !active(f) || f.refused.contains(pipeline)) {
            return false;
        }
        if (f.compiled.containsKey(pipeline)) {
            return true;
        }
        String why = refusal(f, pipeline);
        if (why != null) {
            f.refused.add(pipeline);
            MetalShadersClient.LOGGER.info("Entity takeover ({}): {} stays the game's ({})", f.program, pipeline.getLocation(), why);
            return false;
        }
        try {
            f.compiled.put(pipeline, compile(f, pipeline));
            return true;
        } catch (Exception e) {
            f.refused.add(pipeline);
            MetalShadersClient.LOGGER.warn("Entity takeover ({}): {} stays the game's: {}", f.program, pipeline.getLocation(), e.getMessage());
            return false;
        }
    }

    /** Why a pipeline isn't served by this family, or null if it is. */
    private static String refusal(Family f, RenderPipeline pipeline) throws RuntimeException {
        List<ColorTargetState> targets = pipeline.getColorTargetStates();
        // The bindings list may hold empty slots (PipelineBuilder skips nulls): only slot 0 may be used.
        if (!pipeline.getVertexFormatBindings().stream().skip(1).allMatch(Objects::isNull)
                || !DefaultVertexFormat.ENTITY.equals(pipeline.getVertexFormatBinding(0))) {
            return "not the ENTITY vertex format";
        }
        if (targets.size() != 1) {
            return targets.size() + " colour targets";
        }
        if (targets.getFirst().blendFunction().isPresent() != f.translucent) {
            return f.translucent ? "not blended" : "blended";
        }
        if (pipeline.getDepthStencilState() == null) {
            return "no depth test";
        }
        // The game binds Sampler0-2 only where its pipeline declares them, and every texture our
        // pipeline declares must be bound: so each game texture the pack reads must be declared.
        Set<String> declared = new HashSet<>();
        BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).forEach(u -> declared.add(u.name()));
        Program p;
        try {
            p = f.program(f.alphaFor(pipeline));
        } catch (ProgramTranslator.TranslationException e) {
            return "translation failed: " + e.getMessage();
        }
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            if (GAME_BOUND.contains(s.name()) && !declared.contains(s.name())) {
                return "it binds no " + s.name();
            }
        }
        return null;
    }

    /** The pack's program with the vanilla pipeline's state (topology, cull, depth, polygon mode, blend). */
    private static CompiledRenderPipeline compile(Family f, RenderPipeline vanilla) throws Exception {
        float alpha = f.alphaFor(vanilla);
        Program p = f.program(alpha);
        ProgramTranslator.Result r = p.result();
        String variant = Float.isNaN(alpha) ? "solid" : "cutout";
        Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/" + f.program + "_" + variant);
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/" + f.program + "/" + vanilla.getLocation().getPath()))
                .withVertexShader(shader).withFragmentShader(shader)
                .withPrimitiveTopology(vanilla.getPrimitiveTopology())
                .withCull(vanilla.isCull())
                .withPolygonMode(vanilla.getPolygonMode())
                .withDepthStencilState(vanilla.getDepthStencilState())
                .withVertexBinding(0, DefaultVertexFormat.ENTITY);
        Optional<BlendFunction> vanillaBlend = vanilla.getColorTargetStates().getFirst().blendFunction();
        for (int i = 0; i < f.writes.size(); i++) {
            int index = f.writes.get(i);
            Optional<BlendFunction> blend = TerrainTakeover.blend(f.properties, f.program, index, vanillaBlend);
            b.withColorTargetState(i, new ColorTargetState(blend, PackChain.targetFormat(index),
                    PackChain.promoted(index) ? ColorTargetState.WRITE_COLOR : ColorTargetState.WRITE_ALL));
        }
        if (f.coverage()) {
            b.withColorTargetState(f.writes.size(), new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL));
        }
        BindGroupLayout.Builder bindings = BindGroupLayout.builder()
                .withUniform(ProgramTranslator.DYNAMIC_TRANSFORMS, UniformType.UNIFORM_BUFFER);
        if (!r.uniforms().isEmpty()) {
            bindings.withUniform(ProgramTranslator.UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
        }
        for (ProgramTranslator.Sampler s : r.samplers()) {
            bindings.withUniform(s.name(), UniformType.COMBINED_IMAGE_SAMPLER);
        }
        b.withBindGroupLayout(bindings.build());
        CompiledRenderPipeline c = RenderSystem.getDevice().compilePipeline(b.build(),
                new LuxiumShaderSource().put(shader, ShaderType.VERTEX, r.vertex()).put(shader, ShaderType.FRAGMENT, r.fragment()),
                Runnable::run).join().finishCompile();
        if (c == null) {
            throw new IllegalStateException("the device returned no pipeline");
        }
        programOf.put(c, p);
        compiledCount++;
        MetalShadersClient.LOGGER.info("Entity takeover: {} -> {} ({}), {} pipelines so far", vanilla.getLocation(), f.program,
                Float.isNaN(alpha) ? "no alpha test" : "alpha > " + alpha, compiledCount);
        return c;
    }

    // ---- Windows (from LevelRendererTerrainMixin), opened with the level's pass suspended -----

    private static boolean saidWindow;

    /** Before the level's solid features. */
    public static void openWindow(Function<String, Object> values) {
        open(SOLID, values);
    }

    /** Before the level's translucent features (after the deferred cut). */
    public static void openTranslucentWindow(Function<String, Object> values) {
        open(TRANSLUCENT, values);
    }

    private static void open(Family f, Function<String, Object> values) {
        f.served = 0;
        if (!active(f)) {
            return;
        }
        if (!saidWindow) {
            saidWindow = true;
            MetalShadersClient.LOGGER.info("Entity window opened (targets ready: {})", targetsReady(f));
        }
        window = f;
        // Inputs and the uniform block, while no pass is open (placeholders may be created, and
        // the deferred passes may have flipped buffers since the frame began).
        f.inputs.clear();
        for (Program p : f.byAlpha.values()) {
            for (ProgramTranslator.Sampler s : p.result().samplers()) {
                if (!GAME_BOUND.contains(s.name())) {
                    f.inputs.put(s.name(), PackChain.inputView(s.name()));
                }
            }
        }
        Program p = f.byAlpha.values().iterator().next();
        if (p.layout().size() > 0) {
            if (f.ubo == null) {
                f.ubo = new MappableRingBuffer(() -> "Luxium " + f.program + " uniforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, p.layout().size());
            } else {
                f.ubo.rotate();   // here and not at the window's end: rotating records a command, so no pass may be open
            }
            try (GpuBufferSlice.MappedView view = f.ubo.currentBuffer().map(false, true)) {
                UniformWriter.write(p.layout(), perFrame(values), p.defaults(), view.data());
            }
        }
    }

    public static void closeWindow() {
        Family f = window;
        releaseHold();
        window = null;
        if (f != null && f.served > 0 && System.nanoTime() - logged > 5_000_000_000L) {
            logged = System.nanoTime();
            MetalShadersClient.LOGGER.info("Entities drawn by the pack this frame ({}): {} draws", f.program, f.served);
        }
    }

    // ---- Per draw (from RenderTypeFeatureRendererMixin / PreparedRenderTypeMixin) --------------

    /** The pack's pass for a served draw, opened if needed (the level's pass must be suspended). */
    public static RenderPass hold() {
        Family f = Objects.requireNonNull(window);
        if (hold == null) {
            RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(() -> "Luxium entities (pack " + f.program + ")");
            for (int index : f.writes) {
                d.withColorAttachment(PackChain.gbufferTarget(index));
            }
            if (f.coverage()) {
                d.withColorAttachment(PackChain.coverageTarget());
            }
            d.withDepthAttachment(Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView());
            hold = RenderSystem.getDevice().createCommandEncoder().createRenderPass(d.build());
        }
        return hold;
    }

    /** Whether the open window's targets exist yet (the chain allocates them at the first frame). */
    public static boolean targetsReady() {
        return window != null && targetsReady(window);
    }

    private static boolean targetsReady(Family f) {
        for (int index : f.writes) {
            if (PackChain.gbufferTarget(index) == null) {
                return false;
            }
        }
        return PackChain.coverageTarget() != null;
    }

    /** Closes the pack's entity pass, if one is open (before any other pass opens). */
    public static void releaseHold() {
        if (hold != null) {
            hold.close();
            hold = null;
        }
    }

    public static void enterDraw() {
        inHold = true;
        window.served++;
    }

    public static void leaveDraw() {
        inHold = false;
    }

    /** Inside a served draw: the pack's pipeline for the vanilla one. */
    public static CompiledRenderPipeline pipelineFor(RenderPipeline vanilla, CompiledRenderPipeline original) {
        if (!inHold || window == null) {
            return original;
        }
        CompiledRenderPipeline c = window.compiled.get(vanilla);
        return c != null ? c : original;
    }

    /** After the pipeline is set: the pack's uniform block and its other textures. */
    public static void bind(RenderPass pass, CompiledRenderPipeline pipeline) {
        Family f = window;
        Program p = inHold && f != null ? programOf.get(pipeline) : null;
        if (p == null) {
            return;
        }
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            GpuTextureView v = f.inputs.get(s.name());
            if (v != null) {
                boolean depthLike = s.name().startsWith("depthtex") || s.name().startsWith("shadowtex");
                pass.setUniform(s.name(), v, depthLike ? nearest : linear);
            }
        }
        if (f.ubo != null && p.layout().size() > 0) {
            pass.setUniform(ProgramTranslator.UNIFORM_BLOCK, f.ubo.currentBuffer());
        }
    }

    /**
     * Frame values for the entity block. Model-view, colour modulator and gl_TextureMatrix[0] come
     * per draw from DynamicTransforms; the rest is the camera's (the same for every draw). The
     * block layout depends only on the program text, so the solid and cutout variants share it.
     */
    private static Function<String, Object> perFrame(Function<String, Object> values) {
        Matrix4f modelView = values.apply("gbufferModelView") instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
        Matrix4f projection = values.apply("gbufferProjection") instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
        Matrix4fc[] textureMatrices = new Matrix4fc[8];
        Arrays.fill(textureMatrices, new Matrix4f());
        textureMatrices[1] = LIGHTMAP_MATRIX;
        return name -> switch (name) {
            case "luxium_ModelViewMatrix" -> modelView;
            case "luxium_ProjectionMatrix" -> projection;
            case "luxium_TextureMatrix" -> textureMatrices;
            case "renderStage" -> STAGE_ENTITIES;
            default -> values.apply(name);
        };
    }
}
