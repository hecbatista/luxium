package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Step 6d-4 (first slice): the level's solid entities and block entities drawn by the pack's
 * gbuffers_entities into the pack's targets plus the coverage mask.
 * <p>
 * Every such draw goes through PreparedRenderType.draw (26.3), called per draw by
 * RenderTypeFeatureRenderer.executeGroup with the level's pass. While the level's solid features
 * are drawn ({@link #openWindow}/{@link #closeWindow}), a draw whose pipeline this serves is
 * handed a pass of the pack's instead (the "hold", kept open across a run of served draws), the
 * level's pass stepping aside ({@link LevelPass#suspend}); a draw it doesn't serve goes back to
 * the level's pass, which closes the hold first. The pipeline is swapped for the pack's program
 * compiled with the vanilla pipeline's state; the game's own draw then binds the per-draw
 * DynamicTransforms block and the skin (Sampler0), and the pack's block and textures are bound
 * right after the pipeline. This follows Vitrail's notes (docs/internals/entities.md, "The door is
 * a group of draws") and Iris's choice of key: the RenderPipeline.
 * <p>
 * Served: pipelines drawn with the game's ENTITY vertex format, no blending, one colour target.
 * Iris's alpha tests: ENTITIES_SOLID off, ENTITIES_CUTOUT one tenth (the vanilla pipelines that
 * define ALPHA_CUTOUT).
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

    private static String[] glsl;
    private static final Map<Float, Program> byAlpha = new HashMap<>();
    private static final Map<RenderPipeline, CompiledRenderPipeline> compiled = new IdentityHashMap<>();
    private static final Map<CompiledRenderPipeline, Program> programOf = new IdentityHashMap<>();
    private static final Set<RenderPipeline> refused = new HashSet<>();
    private static final Map<String, GpuTextureView> inputs = new HashMap<>();
    private static List<Integer> writes = List.of();
    private static boolean prepared;
    private static int compiledCount;

    // Per-frame state.
    private static boolean window;
    private static RenderPass hold;
    private static boolean inHold;
    private static MappableRingBuffer ubo;
    private static int servedThisFrame;
    private static long logged;

    private EntityTakeover() {
    }

    /** Debug: LUXIUM_NO_ENTITIES=1 leaves entities to the game's shaders, for before/after shots. */
    private static final boolean SWITCHED_OFF = System.getenv("LUXIUM_NO_ENTITIES") != null;

    public static boolean active() {
        return prepared && !SWITCHED_OFF && PackChain.ready();
    }

    /** Translates gbuffers_entities once per alpha test; pipelines are compiled on first use. */
    public static void prepare(String[] entities) {
        try {
            glsl = entities;
            Program solid = program(Float.NaN);
            program(0.1f);
            writes = solid.result().drawBuffers().isEmpty() ? List.of(0) : solid.result().drawBuffers();
            PackChain.requireTargets(writes);
            prepared = true;
            MetalShadersClient.LOGGER.info("Entity takeover ready: gbuffers_entities writes colortex{} plus the coverage mask; samplers {}",
                    writes, solid.result().samplers().stream().map(ProgramTranslator.Sampler::name).toList());
        } catch (Exception e) {
            MetalShadersClient.LOGGER.error("Entity takeover disabled: {}", e.getMessage(), e);
        }
    }

    private static Program program(float alpha) throws ProgramTranslator.TranslationException {
        Program p = byAlpha.get(alpha);
        if (p == null) {
            ProgramTranslator.Result r = ProgramTranslator.translateForDraw(glsl[0], glsl[1], ELEMENTS,
                    new ProgramTranslator.Format(alpha, true, RENAMES));
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

    /** Whether this vanilla pipeline is one the pack's entity program draws. */
    public static boolean serves(RenderPipeline pipeline) {
        if (!window || !active() || refused.contains(pipeline)) {
            return false;
        }
        if (compiled.containsKey(pipeline)) {
            return true;
        }
        List<ColorTargetState> targets = pipeline.getColorTargetStates();
        // The bindings list may hold empty slots (PipelineBuilder skips nulls): only slot 0 may be used.
        boolean onlySlot0 = pipeline.getVertexFormatBindings().stream().skip(1).allMatch(java.util.Objects::isNull);
        boolean ok = onlySlot0 && DefaultVertexFormat.ENTITY.equals(pipeline.getVertexFormatBinding(0))
                && targets.size() == 1 && targets.getFirst().blendFunction().isEmpty()
                && pipeline.getDepthStencilState() != null;
        if (!ok) {
            refused.add(pipeline);
            MetalShadersClient.LOGGER.info("Entity takeover: {} stays the game's (format {}, {} targets, blend {}, depth {})",
                    pipeline.getLocation(), DefaultVertexFormat.ENTITY.equals(pipeline.getVertexFormatBinding(0)) ? "ENTITY" : "other",
                    targets.size(), targets.isEmpty() ? "-" : targets.getFirst().blendFunction().isPresent(),
                    pipeline.getDepthStencilState() != null);
            return false;
        }
        try {
            compiled.put(pipeline, compile(pipeline));
            return true;
        } catch (Exception e) {
            refused.add(pipeline);
            MetalShadersClient.LOGGER.warn("Entity takeover: {} stays the game's: {}", pipeline.getLocation(), e.getMessage());
            return false;
        }
    }

    /** The pack's program with the vanilla pipeline's state (topology, cull, depth, polygon mode). */
    private static CompiledRenderPipeline compile(RenderPipeline vanilla) throws Exception {
        String cutout = vanilla.getShaderDefines().values().get("ALPHA_CUTOUT");
        float alpha = cutout != null ? 0.1f : Float.NaN;
        Program p = program(alpha);
        ProgramTranslator.Result r = p.result();
        Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/gbuffers_entities_" + (cutout != null ? "cutout" : "solid"));
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/entities/" + vanilla.getLocation().getPath()))
                .withVertexShader(shader).withFragmentShader(shader)
                .withPrimitiveTopology(vanilla.getPrimitiveTopology())
                .withCull(vanilla.isCull())
                .withPolygonMode(vanilla.getPolygonMode())
                .withDepthStencilState(vanilla.getDepthStencilState())
                .withVertexBinding(0, DefaultVertexFormat.ENTITY);
        for (int i = 0; i < writes.size(); i++) {
            int index = writes.get(i);
            b.withColorTargetState(i, new ColorTargetState(Optional.empty(), PackChain.targetFormat(index),
                    PackChain.promoted(index) ? ColorTargetState.WRITE_COLOR : ColorTargetState.WRITE_ALL));
        }
        b.withColorTargetState(writes.size(), new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL));
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
        MetalShadersClient.LOGGER.info("Entity takeover: {} -> gbuffers_entities ({}), {} pipelines so far",
                vanilla.getLocation(), cutout != null ? "alpha > 0.1" : "no alpha test", compiledCount);
        return c;
    }

    // ---- The window: the level's solid features (from LevelRendererTerrainMixin) --------------

    /** Before the level's solid features, with the level's pass suspended (no pass open). */
    private static boolean saidWindow;

    public static void openWindow(Function<String, Object> values) {
        servedThisFrame = 0;
        if (!saidWindow) {
            saidWindow = true;
            MetalShadersClient.LOGGER.info("Entity window opened (active: {}, targets ready: {})", active(), targetsReady());
        }
        if (!active()) {
            return;
        }
        window = true;
        // Inputs and the uniform block, while no pack pass is open (placeholders may be created).
        inputs.clear();
        for (Program p : byAlpha.values()) {
            for (ProgramTranslator.Sampler s : p.result().samplers()) {
                if (!GAME_BOUND.contains(s.name())) {
                    inputs.put(s.name(), PackChain.inputView(s.name()));
                }
            }
        }
        Program p = byAlpha.get(Float.NaN);
        if (p.layout().size() > 0) {
            if (ubo == null) {
                ubo = new MappableRingBuffer(() -> "Luxium entity uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, p.layout().size());
            } else {
                ubo.rotate();   // here and not at the window's end: rotating records a command, so no pass may be open
            }
            try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
                UniformWriter.write(p.layout(), perFrame(values), p.defaults(), view.data());
            }
        }
    }

    public static void closeWindow() {
        releaseHold();
        window = false;
        if (servedThisFrame > 0 && System.nanoTime() - logged > 5_000_000_000L) {
            logged = System.nanoTime();
            MetalShadersClient.LOGGER.info("Entities drawn by the pack this frame: {} draws", servedThisFrame);
        }
    }

    // ---- Per draw (from RenderTypeFeatureRendererMixin / PreparedRenderTypeMixin) --------------

    /** The pack's pass for a served draw, opened if needed (the level's pass must be suspended). */
    public static RenderPass hold() {
        if (hold == null) {
            RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(() -> "Luxium entities (pack gbuffers_entities)");
            for (int index : writes) {
                d.withColorAttachment(PackChain.gbufferTarget(index));
            }
            d.withColorAttachment(PackChain.coverageTarget());
            d.withDepthAttachment(Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView());
            hold = RenderSystem.getDevice().createCommandEncoder().createRenderPass(d.build());
        }
        return hold;
    }

    /** Whether the pack's targets exist yet (the chain allocates them at the first frame). */
    public static boolean targetsReady() {
        for (int index : writes) {
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
        servedThisFrame++;
    }

    public static void leaveDraw() {
        inHold = false;
    }

    /** Inside a served draw: the pack's pipeline for the vanilla one. */
    public static CompiledRenderPipeline pipelineFor(RenderPipeline vanilla, CompiledRenderPipeline original) {
        if (!inHold) {
            return original;
        }
        CompiledRenderPipeline c = compiled.get(vanilla);
        return c != null ? c : original;
    }

    /** After the pipeline is set: the pack's uniform block and its other textures. */
    public static void bind(RenderPass pass, CompiledRenderPipeline pipeline) {
        Program p = inHold ? programOf.get(pipeline) : null;
        if (p == null) {
            return;
        }
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            GpuTextureView v = inputs.get(s.name());
            if (v != null) {
                boolean depthLike = s.name().startsWith("depthtex") || s.name().startsWith("shadowtex");
                pass.setUniform(s.name(), v, depthLike ? nearest : linear);
            }
        }
        if (ubo != null && p.layout().size() > 0) {
            pass.setUniform(ProgramTranslator.UNIFORM_BLOCK, ubo.currentBuffer());
        }
    }

    /**
     * Frame values for the entity block. Model-view, colour modulator and gl_TextureMatrix[0] come
     * per draw from DynamicTransforms; the rest is the camera's (the same for every draw).
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
