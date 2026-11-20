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
        /** The program whose blend.* directives apply (the one found through the fallbacks). */
        String blendProgram;
        List<Integer> writes = List.of();
        final int stage;
        final com.mojang.renderpearl.api.vertex.VertexFormat format;
        final Set<String> elements;
        /** Iris's alpha test for the family when it doesn't follow the pipeline (particles: one tenth). */
        final boolean alwaysTenth;
        /** The hand: Iris's depth squeeze, and the hand's projection (see openHandWindow). */
        boolean hand;
        boolean prepared;
        /** One uniform buffer per draw this frame (each draw has its own ids). */
        final List<MappableRingBuffer> rings = new java.util.ArrayList<>();
        int slot;
        int served;

        Family(String program, boolean translucent, int stage) {
            this(program, translucent, stage, DefaultVertexFormat.ENTITY, ELEMENTS, false);
        }

        Family(String program, boolean translucent, int stage, com.mojang.renderpearl.api.vertex.VertexFormat format,
               Set<String> elements, boolean alwaysTenth) {
            this.program = program;
            this.translucent = translucent;
            this.stage = stage;
            this.format = format;
            this.elements = elements;
            this.alwaysTenth = alwaysTenth;
        }

        boolean coverage() {
            return !translucent;   // translucents are drawn after the seed, which is all the mask is for
        }

        Program program(float alpha) throws ProgramTranslator.TranslationException {
            Program p = byAlpha.get(alpha);
            if (p == null) {
                ProgramTranslator.Result r = ProgramTranslator.translateForDraw(glsl[0], glsl[1], elements,
                        new ProgramTranslator.Format(alpha, coverage(), RENAMES, hand));
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
            if (alwaysTenth) {
                // Iris ONE_TENTH_ALPHA, unless the pack's alphaTest.<program> says otherwise
                return TerrainTakeover.alphaTest(properties, blendProgram != null ? blendProgram : program, 0.1f);
            }
            if (translucent) {
                return 0.1f;   // Iris: translucent entities ONE_TENTH_ALPHA
            }
            return pipeline.getShaderDefines().values().containsKey("ALPHA_CUTOUT") ? 0.1f : Float.NaN;
        }
    }

    /** Iris WorldRenderingPhase: ENTITIES 11, BLOCK_ENTITIES 12. */
    private static final Family SOLID = new Family("gbuffers_entities", false, 11);
    private static final Family BLOCK = new Family("gbuffers_block", false, 12);
    private static final Family TRANSLUCENT = new Family("gbuffers_entities_translucent", true, 11);
    /** Particles (Iris ShaderKey PARTICLES / PARTICLES_TRANS: the PARTICLE format, one tenth; WorldRenderingPhase.PARTICLES = 19). */
    private static final Set<String> PARTICLE_ELEMENTS = Set.of("Position", "UV0", "Color", "UV2");
    private static final Family PARTICLES = new Family("gbuffers_particles", false, 19, DefaultVertexFormat.PARTICLE, PARTICLE_ELEMENTS, true);
    private static final Family PARTICLES_TRANSLUCENT = new Family("gbuffers_particles_translucent", true, 19,
            DefaultVertexFormat.PARTICLE, PARTICLE_ELEMENTS, true);
    /** Rain and snow (Iris ShaderKey WEATHER: the PARTICLE format, one tenth; WorldRenderingPhase.RAIN_SNOW = 21). */
    private static final Family WEATHER = new Family("gbuffers_weather", true, 21, DefaultVertexFormat.PARTICLE, PARTICLE_ELEMENTS, true);
    /** The hand (Iris ShaderKey HAND_CUTOUT / HAND_TRANSLUCENT: one tenth; WorldRenderingPhase HAND_SOLID 16, HAND_TRANSLUCENT 23). */
    private static final Family HAND = new Family("gbuffers_hand", false, 16, DefaultVertexFormat.ENTITY, ELEMENTS, true);
    private static final Family HAND_TRANSLUCENT = new Family("gbuffers_hand_water", true, 23, DefaultVertexFormat.ENTITY, ELEMENTS, true);
    private static final List<Family> ALL = List.of(SOLID, BLOCK, TRANSLUCENT, PARTICLES, PARTICLES_TRANSLUCENT, WEATHER, HAND, HAND_TRANSLUCENT);

    static {
        HAND.hand = true;
        HAND_TRANSLUCENT.hand = true;
    }

    /** Which part of the level's features is being drawn. */
    private enum Phase { SOLID, TRANSLUCENT, HAND }
    private static final Map<CompiledRenderPipeline, Program> programOf = new IdentityHashMap<>();
    private static int compiledCount;

    // Per-frame state.
    private static Phase window;
    private static Function<String, Object> frameValues;
    /** The family and ids of the draw being made (null outside a served draw). */
    private static Family drawing;
    private static DrawIds.Ids drawingIds;
    private static Family holdFamily;
    private static final Set<String> seenIds = new HashSet<>();
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
    public static void prepare(String[] solid, String[] translucent, String[] block, Properties properties) {
        prepareFamily(SOLID, solid, properties);
        prepareFamily(TRANSLUCENT, translucent != null ? translucent : solid, properties);
        if (block != null) {
            prepareFamily(BLOCK, block, properties);
        }
    }

    /**
     * The particle programs, already resolved through Iris's fallbacks (particles -> textured_lit
     * -> textured -> basic; particles_translucent -> particles). {@code name} is the program found.
     */
    public static void prepareParticles(String name, String[] particles, String translucentName, String[] translucent, Properties properties) {
        prepareFamily(PARTICLES, particles, properties, name);
        prepareFamily(PARTICLES_TRANSLUCENT, translucent, properties, translucentName);
    }

    /**
     * The hand programs, already resolved through Iris's fallbacks (hand -> textured_lit -> ...;
     * hand_water -> hand). {@code name}s are the programs found.
     */
    public static void prepareHand(String name, String[] hand, String translucentName, String[] translucent, Properties properties) {
        prepareFamily(HAND, hand, properties, name);
        prepareFamily(HAND_TRANSLUCENT, translucent, properties, translucentName);
    }

    public static boolean handActive() {
        return active(HAND);
    }

    /** The hand's projection (the hud's, OpenGL convention) and depth, for the hand window. */
    private static Matrix4f handProjection;
    private static GpuTextureView holdDepth;

    /**
     * Around the game's hand draw (GameRenderer.renderItemInHand): the hand's draws go to the
     * pack's hand programs, under the hand's projection, depth-tested against the hand's depth.
     */
    public static void openHandWindow(Function<String, Object> values, Matrix4f projection, GpuTextureView depth) {
        handProjection = projection;
        holdDepth = depth;
        open(Phase.HAND, values, List.of(HAND, HAND_TRANSLUCENT));
    }

    /** The weather program, already resolved through Iris's fallbacks (weather -> textured_lit -> textured -> basic). */
    public static void prepareWeather(String name, String[] weather, Properties properties) {
        prepareFamily(WEATHER, weather, properties, name);
    }

    private static void prepareFamily(Family f, String[] glsl, Properties properties, String program) {
        if (glsl == null) {
            return;
        }
        f.blendProgram = program;
        prepareFamily(f, glsl, properties);
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

    /**
     * The family that draws this draw in the open window, or null if it stays the game's. A block
     * entity's solid draws go to gbuffers_block (Iris ProgramId.Block), falling back to
     * gbuffers_entities if the pack has none.
     */
    private static Family familyFor(DrawIds.Ids ids, RenderPipeline pipeline) {
        if (window == Phase.HAND) {
            boolean blended = pipeline.getColorTargetStates().size() == 1 && pipeline.getColorTargetStates().getFirst().blendFunction().isPresent();
            return blended ? HAND_TRANSLUCENT : HAND;
        }
        if (window == Phase.TRANSLUCENT) {
            return TRANSLUCENT;
        }
        if (window == Phase.SOLID) {
            return ids != null && ids.blockEntity() && BLOCK.prepared ? BLOCK : SOLID;
        }
        return null;
    }

    /** Whether the open window serves this draw; if so, the next {@link #hold()} and draw use its family. */
    public static boolean serves(net.minecraft.client.renderer.rendertype.PreparedRenderType type) {
        DrawIds.Ids ids = DrawIds.of(type);
        RenderPipeline pipeline = type.pipeline();
        Family f = familyFor(ids, pipeline);
        if (f == null || !active(f) || !targetsReady(f) || f.refused.contains(pipeline)) {
            return false;
        }
        if (!f.compiled.containsKey(pipeline)) {
            String why = refusal(f, pipeline);
            if (why != null) {
                f.refused.add(pipeline);
                MetalShadersClient.LOGGER.info("Entity takeover ({}): {} stays the game's ({})", f.program, pipeline.getLocation(), why);
                return false;
            }
            try {
                f.compiled.put(pipeline, compile(f, pipeline));
            } catch (Exception e) {
                f.refused.add(pipeline);
                MetalShadersClient.LOGGER.warn("Entity takeover ({}): {} stays the game's: {}", f.program, pipeline.getLocation(), e.getMessage());
                return false;
            }
        }
        drawing = f;
        drawingIds = ids;
        if (seenIds.add(f.program + " " + type.name() + " " + ids)) {
            MetalShadersClient.LOGGER.info("Entity draw {} ({}): {}", type.name(), f.program,
                    ids == null ? "no ids (not an entity's or block entity's)" : "entityId " + ids.entityId() + ", blockEntityId " + ids.blockEntityId());
        }
        return true;
    }

    /** Why a pipeline isn't served by this family, or null if it is. */
    private static String refusal(Family f, RenderPipeline pipeline) throws RuntimeException {
        List<ColorTargetState> targets = pipeline.getColorTargetStates();
        // The bindings list may hold empty slots (PipelineBuilder skips nulls): only slot 0 may be used.
        if (!pipeline.getVertexFormatBindings().stream().skip(1).allMatch(Objects::isNull)
                || !f.format.equals(pipeline.getVertexFormatBinding(0))) {
            return "not the vertex format " + f.program + " is drawn with";
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
                .withVertexBinding(0, f.format);
        Optional<BlendFunction> vanillaBlend = vanilla.getColorTargetStates().getFirst().blendFunction();
        for (int i = 0; i < f.writes.size(); i++) {
            int index = f.writes.get(i);
            Optional<BlendFunction> blend = TerrainTakeover.blend(f.properties, f.blendProgram != null ? f.blendProgram : f.program, index, vanillaBlend);
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

    /** Frame start (from PackChain.beginFrame, no pass open): last frame's uniform buffers rotate. */
    static void beginFrame() {
        for (Family f : ALL) {
            for (int i = 0; i < f.slot; i++) {
                f.rings.get(i).rotate();
            }
            f.slot = 0;
        }
        DrawIds.beginFrame();
    }

    /** Before the level's solid features. */
    public static void openWindow(Function<String, Object> values) {
        open(Phase.SOLID, values, List.of(SOLID, BLOCK, PARTICLES));
    }

    /** Before the level's translucent features, and its "after terrain" ones (after the deferred cut). */
    public static void openTranslucentWindow(Function<String, Object> values) {
        open(Phase.TRANSLUCENT, values, List.of(TRANSLUCENT, PARTICLES_TRANSLUCENT));
    }

    private static void open(Phase phase, Function<String, Object> values, List<Family> families) {
        window = phase;
        frameValues = perFrame(values);
        for (Family f : families) {
            f.served = 0;
            if (!active(f)) {
                continue;
            }
            if (!saidWindow) {
                saidWindow = true;
                MetalShadersClient.LOGGER.info("Entity window opened (targets ready: {})", targetsReady(f));
            }
            // Inputs while no pass is open (placeholders may be created, and the deferred passes
            // may have flipped buffers since the frame began).
            f.inputs.clear();
            for (Program p : f.byAlpha.values()) {
                for (ProgramTranslator.Sampler s : p.result().samplers()) {
                    if (!GAME_BOUND.contains(s.name())) {
                        f.inputs.put(s.name(), PackChain.inputView(s.name()));
                    }
                }
            }
        }
    }

    public static void closeWindow() {
        releaseHold();
        window = null;
        handProjection = null;
        holdDepth = null;
        particleFamily = null;
        for (Family f : ALL) {
            if (f.served > 0 && System.nanoTime() - logged > 5_000_000_000L) {
                logged = System.nanoTime();
                MetalShadersClient.LOGGER.info("Entities drawn by the pack this frame ({}): {} draws", f.program, f.served);
            }
        }
    }

    // ---- Particles (from QuadParticleFeatureRendererMixin, through RoutingPass) ---------------

    private static Family particleFamily;

    /** A routing pass for a particle group in the open window, or null to leave it to the game. */
    public static RoutingPass routeParticles(RenderPass pass) {
        return route(pass, window == Phase.SOLID ? PARTICLES : window == Phase.TRANSLUCENT ? PARTICLES_TRANSLUCENT : null);
    }

    /**
     * A routing pass for the weather (WeatherEffectRenderer.render, inside the level's translucent
     * phase, after the deferred cut), or null to leave it to the game.
     */
    public static RoutingPass routeWeather(RenderPass pass, Function<String, Object> values) {
        if (!(pass instanceof LevelPass level) || !active(WEATHER)) {
            return null;
        }
        level.suspend();   // the window resolves textures: no pass may be open
        open(Phase.TRANSLUCENT, values, List.of(WEATHER));
        return route(pass, WEATHER);
    }

    private static RoutingPass route(RenderPass pass, Family f) {
        if (!(pass instanceof LevelPass level) || f == null || !active(f) || !targetsReady(f)) {
            return null;
        }
        particleFamily = f;
        return new RoutingPass(level);
    }

    /** Inside a routed particle group: the pack's pipeline for a layer's, if the family serves it. */
    public static CompiledRenderPipeline particlePipelineFor(RenderPipeline vanilla, CompiledRenderPipeline original) {
        Family f = particleFamily;
        if (f == null || f.refused.contains(vanilla)) {
            return original;
        }
        CompiledRenderPipeline c = f.compiled.get(vanilla);
        if (c == null) {
            String why = refusal(f, vanilla);
            if (why != null) {
                f.refused.add(vanilla);
                MetalShadersClient.LOGGER.info("Particle takeover ({}): {} stays the game's ({})", f.program, vanilla.getLocation(), why);
                return original;
            }
            try {
                c = compile(f, vanilla);
                f.compiled.put(vanilla, c);
            } catch (Exception e) {
                f.refused.add(vanilla);
                MetalShadersClient.LOGGER.warn("Particle takeover ({}): {} stays the game's: {}", f.program, vanilla.getLocation(), e.getMessage());
                return original;
            }
        }
        return c;
    }

    /** From RoutingPass.setPipeline: whether this compiled pipeline is one of the particle family's. */
    public static boolean isPackParticlePipeline(CompiledRenderPipeline pipeline) {
        Family f = particleFamily;
        if (f == null || !f.compiled.containsValue(pipeline)) {
            return false;
        }
        drawing = f;
        drawingIds = null;
        inHold = true;
        f.served++;
        return true;
    }

    /** The pack's pass for the particle family (the level's pass already suspended). */
    public static RenderPass particleHold() {
        return hold();
    }

    public static void endParticleDraw() {
        if (particleFamily != null) {
            inHold = false;
            drawing = null;
        }
    }

    /** End of a routed particle group. */
    public static void endParticles() {
        endParticleDraw();
        particleFamily = null;
    }

    // ---- Per draw (from RenderTypeFeatureRendererMixin / PreparedRenderTypeMixin) --------------

    /** The pack's pass for a served draw, opened if needed (the level's pass must be suspended). */
    public static RenderPass hold() {
        Family f = Objects.requireNonNull(drawing);
        if (hold != null && holdFamily != f && !holdFamily.writes.equals(f.writes)) {
            releaseHold();   // another program with other draw buffers: another pass
        }
        if (hold == null) {
            holdFamily = f;
            RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(() -> "Luxium entities (pack " + f.program + ")");
            for (int index : f.writes) {
                d.withColorAttachment(PackChain.gbufferTarget(index));
            }
            if (f.coverage()) {
                d.withColorAttachment(PackChain.coverageTarget());
            }
            d.withDepthAttachment(holdDepth != null ? holdDepth : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView());
            hold = RenderSystem.getDevice().createCommandEncoder().createRenderPass(d.build());
        }
        return hold;
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
        drawing.served++;
    }

    public static void leaveDraw() {
        inHold = false;
        drawing = null;
        drawingIds = null;
    }

    /** Inside a served draw: the pack's pipeline for the vanilla one. */
    public static CompiledRenderPipeline pipelineFor(RenderPipeline vanilla, CompiledRenderPipeline original) {
        if (!inHold || drawing == null) {
            return original;
        }
        CompiledRenderPipeline c = drawing.compiled.get(vanilla);
        return c != null ? c : original;
    }

    /** After the pipeline is set: the pack's uniform block and its other textures. */
    public static void bind(RenderPass pass, CompiledRenderPipeline pipeline) {
        Family f = drawing;
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
        if (p.layout().size() > 0) {
            // A buffer per draw: the ids differ from draw to draw. Mapping is CPU work, so it's
            // fine inside the pass; rotating isn't, and happens at frame start.
            while (f.rings.size() <= f.slot) {
                f.rings.add(new MappableRingBuffer(() -> "Luxium " + f.program + " uniforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, p.layout().size()));
            }
            MappableRingBuffer ring = f.rings.get(f.slot++);
            DrawIds.Ids ids = drawingIds;
            Function<String, Object> values = frameValues;
            int stage = f.stage;
            Function<String, Object> perDraw = name -> switch (name) {
                case "entityId" -> ids == null ? 0 : ids.entityId();
                case "blockEntityId" -> ids == null ? 0 : ids.blockEntityId();
                case "renderStage" -> stage;
                default -> values.apply(name);
            };
            try (GpuBufferSlice.MappedView view = ring.currentBuffer().map(false, true)) {
                UniformWriter.write(p.layout(), perDraw, p.defaults(), view.data());
            }
            pass.setUniform(ProgramTranslator.UNIFORM_BLOCK, ring.currentBuffer());
        }
    }

    /**
     * Frame values for the entity block. Model-view, colour modulator and gl_TextureMatrix[0] come
     * per draw from DynamicTransforms; the rest is the camera's (the same for every draw). The
     * block layout depends only on the program text, so the solid and cutout variants share it.
     */
    private static Function<String, Object> perFrame(Function<String, Object> values) {
        Matrix4f modelView = values.apply("gbufferModelView") instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
        // The hand is drawn under the hud projection (its own field of view), and that's what the
        // hand programs' gl_ProjectionMatrix is (Vitrail frame.md: "the one family whose
        // gl_ProjectionMatrix is not the frame's"); gbufferProjection stays the world's.
        Matrix4f projection = handProjection != null ? new Matrix4f(handProjection)
                : values.apply("gbufferProjection") instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
        Matrix4fc[] textureMatrices = new Matrix4fc[8];
        Arrays.fill(textureMatrices, new Matrix4f());
        textureMatrices[1] = LIGHTMAP_MATRIX;
        return name -> switch (name) {
            case "luxium_ModelViewMatrix" -> modelView;
            case "luxium_ProjectionMatrix" -> projection;
            case "luxium_TextureMatrix" -> textureMatrices;
            default -> values.apply(name);
        };
    }
}
