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
import net.minecraft.client.renderer.RenderPipelines;
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
    /**
     * One translation of a family's program, with its own per-draw uniform buffers. Variants of one
     * program differ in block size (an overlay-less variant keeps entityColor in the block, a glint
     * variant adds the glint strength), so each variant's buffers are sized for its own layout.
     */
    private static final class Program {
        final ProgramTranslator.Result result;
        final Std140Layout.Layout layout;
        final Map<String, String> defaults;
        final String label;
        /** One uniform buffer per draw this frame (each draw has its own ids); the first {@link #slot} are in use. */
        final List<MappableRingBuffer> rings = new java.util.ArrayList<>();
        int slot;

        Program(ProgramTranslator.Result result, Std140Layout.Layout layout, Map<String, String> defaults, String label) {
            this.result = result;
            this.layout = layout;
            this.defaults = defaults;
            this.label = label;
        }

        ProgramTranslator.Result result() {
            return result;
        }

        Std140Layout.Layout layout() {
            return layout;
        }

        Map<String, String> defaults() {
            return defaults;
        }

        /** The next free uniform buffer for a draw, sized for this variant's block. */
        MappableRingBuffer nextRing() {
            while (rings.size() <= slot) {
                rings.add(new MappableRingBuffer(() -> "Luxium " + label + " uniforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, layout.size()));
            }
            return rings.get(slot++);
        }

        /** Frame start (no pass open): the buffers used last frame rotate. */
        void rotate() {
            for (int i = 0; i < slot; i++) {
                rings.get(i).rotate();
            }
            slot = 0;
        }

        void close() {
            rings.forEach(MappableRingBuffer::close);
            rings.clear();
            slot = 0;
        }
    }

    /**
     * How a vanilla pipeline draws the enchantment glint. 26.3 draws it inline: GLINT_SNIPPET adds
     * the shader define GLINT and a GlintSampler, and the draw's TextureMat is then the glint's
     * scrolling matrix (entity.vsh / item.vsh texCoordGlint). GLINT_SPECIAL pipelines (compasses
     * and clocks) use the ENTITY_GLINT_SPECIAL format, whose UV3 carries the glint's coordinate.
     */
    private enum Glint {
        NONE, STANDARD, SPECIAL;

        static Glint of(RenderPipeline pipeline) {
            if (!pipeline.getShaderDefines().flags().contains("GLINT")) {
                return NONE;
            }
            return DefaultVertexFormat.ENTITY_GLINT_SPECIAL.equals(pipeline.getVertexFormatBinding(0)) ? SPECIAL : STANDARD;
        }
    }

    /**
     * Pack albedo names -> the game's skin sampler; lightmap -> the game's lightmap sampler.
     * Not "texture": NameFixes already renames a sampler declared as texture to gtexture, and the
     * rename hits every identifier, so it turned GLSL's texture() calls into Sampler0() (Solas's
     * shadow program, 2026-11-23).
     */
    private static final Map<String, String> RENAMES = Map.of(
            "tex", "Sampler0", "gtexture", "Sampler0", "gcolor", "Sampler0", "lightmap", "Sampler2");
    private static final Set<String> GAME_BOUND = Set.of("Sampler0", "Sampler1", "Sampler2", ProgramTranslator.GLINT_SAMPLER);
    private static final Matrix4f LIGHTMAP_MATRIX = new Matrix4f(
            0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0.03125f, 0.03125f, 0.03125f, 1);
    private static final Set<String> ELEMENTS = Set.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");

    /** One pack program and the vanilla pipelines it serves. */
    private static final class Family {
        final String program;
        final boolean translucent;
        /** Translations by alpha test, by whether the draw binds an overlay texture, and by glint. */
        final Map<String, Program> byAlpha = new HashMap<>();
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
        /** Drawn into the shadow map: the light's matrices, forward depth, the shadow targets. */
        boolean shadow;
        /** Serves blended and unblended pipelines alike (the shadow pass draws both, unblended). */
        boolean anyBlend;
        /** The vanilla pipeline this family is for, by location (null: chosen by phase). */
        String pipelinePath;
        /** Iris's fixed alpha test for the family (NaN = off), or null for the usual rules. */
        Float alphaOverride;
        /** Iris's default blend override for the program (ProgramId), used where the pack sets none. */
        Optional<BlendFunction> blendOverride;
        /**
         * Blended, but drawn before the deferred cut over geometry the pack already drew (banner
         * patterns): it writes the coverage mask too, so it shares the solid families' pass.
         */
        boolean coverageAlways;
        boolean prepared;
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
            // translucents are drawn after the seed, which is all the mask is for; the shadow map has none
            return (!translucent || coverageAlways) && !shadow;
        }

        Program program(float alpha) throws ProgramTranslator.TranslationException {
            return program(alpha, true, Glint.NONE);
        }

        Program program(float alpha, boolean overlay, Glint glint) throws ProgramTranslator.TranslationException {
            String key = alpha + "/" + overlay + "/" + glint;
            Program p = byAlpha.get(key);
            if (p == null) {
                Set<String> drawElements = elements;
                if (glint == Glint.SPECIAL) {
                    drawElements = new HashSet<>(elements);
                    drawElements.add("UV3");
                }
                ProgramTranslator.Result r = ProgramTranslator.translateForDraw(PackChain.gbufferVolumes(glsl[0]), PackChain.gbufferVolumes(glsl[1]), drawElements,
                        new ProgramTranslator.Format(alpha, coverage(), RENAMES, hand, overlay, shadow, glint != Glint.NONE));
                Map<String, String> defaults = new HashMap<>();
                r.uniforms().forEach(u -> {
                    if (u.defaultValue() != null) {
                        defaults.put(u.name(), u.defaultValue());
                    }
                });
                p = new Program(r, Std140Layout.of(r.uniforms()), defaults, program + " " + key);
                byAlpha.put(key, p);
            }
            return p;
        }

        /** Whether a pipeline's vertex format is this family's (an ENTITY family also takes the special-glint format). */
        boolean takesFormat(RenderPipeline pipeline) {
            com.mojang.renderpearl.api.vertex.VertexFormat binding = pipeline.getVertexFormatBinding(0);
            return format.equals(binding)
                    || (format.equals(DefaultVertexFormat.ENTITY) && DefaultVertexFormat.ENTITY_GLINT_SPECIAL.equals(binding));
        }

        float alphaFor(RenderPipeline pipeline) {
            if (alphaOverride != null) {
                return alphaOverride;
            }
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

    /** Pipelines Iris gives programs of their own (ShaderKey EMISSIVE_ENTITIES, BEACON, LIGHTNING). */
    private static final Family SPIDEREYES = new Family("gbuffers_spidereyes", true, 11, DefaultVertexFormat.ENTITY, ELEMENTS, true);
    private static final Set<String> BLOCK_ELEMENTS = Set.of("Position", "Color", "UV0", "UV2");
    private static final Family BEACON = new Family("gbuffers_beaconbeam", false, 12, DefaultVertexFormat.BLOCK, BLOCK_ELEMENTS, false);
    private static final Family BEACON_TRANSLUCENT = new Family("gbuffers_beaconbeam", true, 12, DefaultVertexFormat.BLOCK, BLOCK_ELEMENTS, false);
    private static final Family LIGHTNING = new Family("gbuffers_lightning", true, 11, DefaultVertexFormat.POSITION_COLOR,
            Set.of("Position", "Color"), false);
    private static final List<Family> BY_PIPELINE = List.of(SPIDEREYES, BEACON, BEACON_TRANSLUCENT, LIGHTNING);
    /**
     * Banner and shield patterns: 26.3 draws them in the solid phase (RenderTypes.bannerPattern:
     * withForcedSolidModelPhase), blended over the pack's white flag, so they must be drawn by the
     * pack too, or final paints over them. Iris IrisPipelines: BANNER_PATTERN -> getTranslucent,
     * i.e. ShaderKey BE_TRANSLUCENT (ProgramId.BlockTrans, block_translucent -> block) for a block
     * entity and ENTITIES_TRANSLUCENT otherwise, both one tenth.
     */
    private static final String BANNER_PATTERN = "pipeline/banner_pattern";
    private static final Family BLOCK_TRANSLUCENT = new Family("gbuffers_block_translucent", true, 12, DefaultVertexFormat.ENTITY, ELEMENTS, true);
    private static final Family ENTITY_PATTERNS = new Family("gbuffers_entities_translucent", true, 11, DefaultVertexFormat.ENTITY, ELEMENTS, true);
    /**
     * Text-format draws in the hand: a held map (FirstPersonHandsAndItemsRenderer.renderMap draws
     * its background and contents with RenderTypes.text, POSITION_TEX_LIGHTMAP_COLOR). Iris
     * IrisPipelines.getText: HAND_TEXT in its solid hand pass (ProgramId.Hand, NON_ZERO_ALPHA).
     */
    private static final Family HAND_TEXT = new Family("gbuffers_hand", false, 16, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR,
            Set.of("Position", "UV0", "UV2", "Color"), false);
    /**
     * Text in the world: signs, name tags, text displays (RenderTypes.text and textPolygonOffset,
     * POSITION_TEX_LIGHTMAP_COLOR, blended). Iris IrisPipelines.getText: ShaderKey TEXT
     * (ProgramId.EntitiesTrans) or, for a block entity, TEXT_BE (ProgramId.BlockTrans), both one
     * tenth. Left to the game, text lands in a buffer only some packs read (Bliss's signs were blank).
     */
    private static final Family TEXT = new Family("gbuffers_entities_translucent", true, 11, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR,
            Set.of("Position", "UV0", "UV2", "Color"), true);
    private static final Family TEXT_BE = new Family("gbuffers_block_translucent", true, 12, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR,
            Set.of("Position", "UV0", "UV2", "Color"), true);
    /** Entities and block entities in the shadow map (Iris ShaderKey SHADOW_ENTITIES_CUTOUT: ProgramId.ShadowEntities -> Shadow, one tenth). */
    private static final Family SHADOW_ENTITIES = new Family("shadow", false, 11, DefaultVertexFormat.ENTITY, ELEMENTS, true);

    static {
        SHADOW_ENTITIES.shadow = true;
        SHADOW_ENTITIES.anyBlend = true;
    }
    /** Every family (declared after all of them: static fields initialise in order). */
    private static final List<Family> ALL = List.of(SOLID, BLOCK, TRANSLUCENT, PARTICLES, PARTICLES_TRANSLUCENT, WEATHER, HAND, HAND_TRANSLUCENT,
            SPIDEREYES, BEACON, BEACON_TRANSLUCENT, LIGHTNING, SHADOW_ENTITIES, BLOCK_TRANSLUCENT, ENTITY_PATTERNS, HAND_TEXT,
            TEXT, TEXT_BE);

    static {
        HAND.hand = true;
        HAND_TRANSLUCENT.hand = true;
        HAND_TEXT.hand = true;
        HAND_TEXT.anyBlend = true;   // the text pipelines are blended; Iris draws them in its solid hand pass
        HAND_TEXT.alphaOverride = 0.0001f;   // Iris AlphaTests.NON_ZERO_ALPHA
        BLOCK_TRANSLUCENT.coverageAlways = true;
        ENTITY_PATTERNS.coverageAlways = true;
        // Block entities' text (signs) is drawn in the SOLID window, before the seed: without the
        // mask the seed put the game's image over every glyph (Complementary's sign text read as
        // the fog colour, pale blue-white).
        TEXT.coverageAlways = true;
        TEXT_BE.coverageAlways = true;
        SPIDEREYES.pipelinePath = "pipeline/eyes";
        // Iris ProgramId.SpiderEyes: SRC_ALPHA ONE ZERO ONE.
        SPIDEREYES.blendOverride = Optional.of(new BlendFunction(com.mojang.renderpearl.api.pipeline.BlendFactor.SRC_ALPHA,
                com.mojang.renderpearl.api.pipeline.BlendFactor.ONE, com.mojang.renderpearl.api.pipeline.BlendFactor.ZERO,
                com.mojang.renderpearl.api.pipeline.BlendFactor.ONE));
        BEACON.pipelinePath = "pipeline/beacon_beam_opaque";
        BEACON.alphaOverride = Float.NaN;
        BEACON_TRANSLUCENT.pipelinePath = "pipeline/beacon_beam_translucent";
        BEACON_TRANSLUCENT.alphaOverride = Float.NaN;
        LIGHTNING.pipelinePath = "pipeline/lightning";
        LIGHTNING.alphaOverride = Float.NaN;
    }

    /** Which part of the level's features is being drawn. */
    private enum Phase { SOLID, TRANSLUCENT, HAND, SHADOW }
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
        prepareFamily(ENTITY_PATTERNS, translucent != null ? translucent : solid, properties,
                translucent != null ? "gbuffers_entities_translucent" : "gbuffers_entities");
        prepareFamily(TEXT, translucent != null ? translucent : solid, properties,
                translucent != null ? "gbuffers_entities_translucent" : "gbuffers_entities");
        if (block != null) {
            prepareFamily(BLOCK, block, properties);
        }
    }

    /**
     * The program for block entities' banner patterns, already resolved through Iris's fallbacks
     * (block_translucent -> block; Luxium then falls back to entities, as for gbuffers_block).
     */
    public static void prepareBlockTranslucent(String name, String[] glsl, Properties properties) {
        prepareFamily(BLOCK_TRANSLUCENT, glsl, properties, name);
        prepareFamily(TEXT_BE, glsl, properties, name);
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
        prepareFamily(HAND_TEXT, hand, properties, name);
    }

    /** A program Iris gives one vanilla pipeline (eyes, beacon beam, lightning), resolved through its fallbacks. */
    public static void prepareByPipeline(String family, String name, String[] glsl, Properties properties) {
        for (Family f : BY_PIPELINE) {
            if (f.program.equals(family)) {
                prepareFamily(f, glsl, properties, name);
            }
        }
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
        open(Phase.HAND, values, List.of(HAND, HAND_TRANSLUCENT, HAND_TEXT));
    }

    /** The shadow program for entities and block entities in the shadow map. */
    public static void prepareShadowEntities(String[] shadow, Properties properties) {
        prepareFamily(SHADOW_ENTITIES, shadow, properties, "shadow");
    }

    /**
     * Inside the shadow pass, after the opaque terrain: the level's solid features (entities, block
     * entities) replayed into the shadow map with the pack's shadow program. Their draw list is
     * already built (the frame was prepared before the level's main pass), and drawing from it only
     * reads buffers, so the replay leaves the real draw untouched. What isn't served is dropped.
     */
    public static void drawShadowCasters(net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame frame,
                                         Function<String, Object> values) {
        if (frame == null || !active(SHADOW_ENTITIES)) {
            return;
        }
        holdDepth = ShadowTargets.depthAttachment();
        open(Phase.SHADOW, values, List.of(SHADOW_ENTITIES));
        inWindow(() -> frame.executeSolid(DropPass.INSTANCE));
    }

    /**
     * Runs {@code body} inside an open window and closes the window afterwards. If the body fails,
     * closing can fail too (a draw that threw between its pushDebugGroup and popDebugGroup leaves
     * the pack's pass with a debug group open, and closing it throws "Render pass had debug
     * groups left open!"); that second failure is attached to the first as suppressed instead of
     * replacing it, so the real cause is what reaches the log.
     */
    public static void inWindow(Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            closeWindowAfter(t);
            throw t;
        }
        closeWindow();
    }

    /** Closes the window after {@code failure}, keeping a failure to close as suppressed. */
    public static void closeWindowAfter(Throwable failure) {
        try {
            closeWindow();
        } catch (Throwable c) {
            failure.addSuppressed(c);
        }
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
        if (window == null) {
            return null;
        }
        if (window == Phase.SHADOW) {
            return SHADOW_ENTITIES;
        }
        if (window != Phase.HAND) {
            for (Family f : BY_PIPELINE) {
                if (f.pipelinePath != null && f.pipelinePath.equals(pipeline.getLocation().getPath())) {
                    return f;
                }
            }
        }
        if (window == Phase.HAND) {
            if (HAND_TEXT.format.equals(pipeline.getVertexFormatBinding(0))) {
                return HAND_TEXT;
            }
            boolean blended = pipeline.getColorTargetStates().size() == 1 && pipeline.getColorTargetStates().getFirst().blendFunction().isPresent();
            return blended ? HAND_TRANSLUCENT : HAND;
        }
        if ((window == Phase.SOLID || window == Phase.TRANSLUCENT) && TEXT.format.equals(pipeline.getVertexFormatBinding(0))) {
            return ids != null && ids.blockEntity() && TEXT_BE.prepared ? TEXT_BE : TEXT;
        }
        if (window == Phase.TRANSLUCENT) {
            return TRANSLUCENT;
        }
        if (window == Phase.SOLID && BANNER_PATTERN.equals(pipeline.getLocation().getPath())) {
            return ids != null && ids.blockEntity() ? BLOCK_TRANSLUCENT : ENTITY_PATTERNS;
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
        if (seenIds.add(f.program + " " + type.name() + " " + ids + " " + window)) {
            MetalShadersClient.LOGGER.info("Entity draw {} ({}, {} window): {}", type.name(), f.program, window,
                    ids == null ? "no ids (not an entity's or block entity's)" : "entityId " + ids.entityId() + ", blockEntityId " + ids.blockEntityId() + ", currentRenderedItemId " + ids.itemId());
        }
        return true;
    }

    /**
     * Step 7f: which game pipelines to compile while the world loads, per family, instead of the
     * first time each is drawn (a first-use compile froze the game for 10-100 ms). Measured on
     * Complementary: compiling everything a family could serve is 136 pipelines and 7-15 s per
     * world, so only the ones every session meets in its first seconds are listed: the player's
     * arm and held item, common mobs, chests, armour, dropped items, particles, rain. The rest
     * still compile when first drawn. Iris compiles all of its programs when the pack loads;
     * Vitrail's notes ask for precompiling (docs/internals/game-graphics-api.md).
     */
    private static final Map<Family, List<RenderPipeline>> PREWARM = new java.util.IdentityHashMap<>();

    static {
        // By constant, not by name: names aren't unique (26.3 has three "pipeline/item_cutout",
        // and RenderPipelines.requiredPipelines keeps only the last of them).
        List<RenderPipeline> solid = List.of(RenderPipelines.ENTITY_SOLID, RenderPipelines.ENTITY_CUTOUT,
                RenderPipelines.ENTITY_CUTOUT_CULL, RenderPipelines.ARMOR_CUTOUT_NO_CULL, RenderPipelines.ITEM_CUTOUT);
        PREWARM.put(SOLID, solid);
        PREWARM.put(SHADOW_ENTITIES, solid);
        PREWARM.put(BLOCK, List.of(RenderPipelines.ENTITY_CUTOUT_CULL, RenderPipelines.ENTITY_SOLID));
        PREWARM.put(TRANSLUCENT, List.of(RenderPipelines.ENTITY_TRANSLUCENT, RenderPipelines.ITEM_TRANSLUCENT, RenderPipelines.ENTITY_SHADOW));
        PREWARM.put(HAND, List.of(RenderPipelines.ITEM_CUTOUT, RenderPipelines.ENTITY_CUTOUT, RenderPipelines.ENTITY_SOLID));
        PREWARM.put(HAND_TRANSLUCENT, List.of(RenderPipelines.ENTITY_TRANSLUCENT, RenderPipelines.ITEM_TRANSLUCENT));
        PREWARM.put(PARTICLES, List.of(RenderPipelines.OPAQUE_PARTICLE));
        PREWARM.put(PARTICLES_TRANSLUCENT, List.of(RenderPipelines.TRANSLUCENT_PARTICLE));
        PREWARM.put(WEATHER, List.of(RenderPipelines.WEATHER));
    }

    /**
     * Compiles the {@link #PREWARM} pipelines of every ready family; returns how many. Step 7g: all
     * of them are started on {@link PipelineCompiler}'s pool first (the GLSL to MSL half, about 95%
     * of a compile), then finished in order here, so they compile side by side instead of one by one.
     */
    public static int prewarm() {
        List<Prepared> prepared = new java.util.ArrayList<>();
        List<java.util.concurrent.CompletableFuture<CompiledRenderPipeline.Pending>> started = new java.util.ArrayList<>();
        for (Map.Entry<Family, List<RenderPipeline>> e : PREWARM.entrySet()) {
            Family f = e.getKey();
            if (!active(f)) {
                continue;
            }
            for (RenderPipeline p : e.getValue()) {
                if (f.compiled.containsKey(p) || f.refused.contains(p)) {
                    continue;
                }
                if (refusal(f, p) != null) {
                    f.refused.add(p);
                    continue;
                }
                try {
                    Prepared prep = prepare(f, p);
                    started.add(PipelineCompiler.start(prep.pipeline(), prep.source()));
                    prepared.add(prep);
                } catch (Exception ex) {
                    f.refused.add(p);
                    MetalShadersClient.LOGGER.warn("Entity takeover ({}): {} stays the game's: {}", f.program, p.getLocation(), ex.getMessage());
                }
            }
        }
        int compiled = 0;
        for (int i = 0; i < prepared.size(); i++) {
            Prepared prep = prepared.get(i);
            try {
                long t0 = System.nanoTime();
                CompiledRenderPipeline c = PipelineCompiler.finish(started.get(i));
                prep.f().compiled.put(prep.vanilla(), done(prep, c, "waited and finished in " + (System.nanoTime() - t0) / 1_000_000 + " ms"));
                compiled++;
            } catch (Exception ex) {
                prep.f().refused.add(prep.vanilla());
                MetalShadersClient.LOGGER.warn("Entity takeover ({}): {} stays the game's: {}", prep.f().program, prep.vanilla().getLocation(), ex.getMessage());
            }
        }
        return compiled;
    }

    /** Why a pipeline isn't served by this family, or null if it is. */
    private static String refusal(Family f, RenderPipeline pipeline) throws RuntimeException {
        List<ColorTargetState> targets = pipeline.getColorTargetStates();
        // The bindings list may hold empty slots (PipelineBuilder skips nulls): only slot 0 may be used.
        if (!pipeline.getVertexFormatBindings().stream().skip(1).allMatch(Objects::isNull) || !f.takesFormat(pipeline)) {
            return "not the vertex format " + f.program + " is drawn with";
        }
        if (targets.size() != 1) {
            return targets.size() + " colour targets";
        }
        if (f.pipelinePath == null && !f.anyBlend && targets.getFirst().blendFunction().isPresent() != f.translucent) {
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
            p = f.program(f.alphaFor(pipeline), declared.contains(ProgramTranslator.OVERLAY_SAMPLER), Glint.of(pipeline));
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
        long t0 = PerfStats.start();
        try {
            return compileTimed(f, vanilla);
        } finally {
            PerfStats.end(PerfStats.Section.COMPILE, t0);
        }
    }

    private static CompiledRenderPipeline compileTimed(Family f, RenderPipeline vanilla) throws Exception {
        Prepared prep = prepare(f, vanilla);
        CompiledRenderPipeline c = PipelineCompiler.compileNow(prep.pipeline(), prep.source());
        return done(prep, c, "translate " + prep.translateMs() + " ms + " + PipelineCompiler.lastSplit());
    }

    /** A pipeline ready to compile: the pack's program with the vanilla pipeline's state. */
    private record Prepared(Family f, RenderPipeline vanilla, Program program, RenderPipeline pipeline,
                            LuxiumShaderSource source, long started, long translateMs) {
    }

    private static Prepared prepare(Family f, RenderPipeline vanilla) throws Exception {
        long started = System.nanoTime();
        float alpha = f.alphaFor(vanilla);
        Set<String> declared = new HashSet<>();
        BindGroupLayout.flattenUniforms(vanilla.getBindGroupLayouts()).forEach(u -> declared.add(u.name()));
        boolean overlay = declared.contains(ProgramTranslator.OVERLAY_SAMPLER);
        Glint glint = Glint.of(vanilla);
        Program p = f.program(alpha, overlay, glint);
        ProgramTranslator.Result r = p.result();
        String variant = (Float.isNaN(alpha) ? "solid" : "cutout") + (overlay ? "" : "_no_overlay")
                + (glint == Glint.NONE ? "" : "_glint" + (glint == Glint.SPECIAL ? "_special" : ""));
        Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/" + f.program + "_" + variant);
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/" + f.program + "/" + vanilla.getLocation().getPath()))
                .withVertexShader(shader).withFragmentShader(shader)
                .withPrimitiveTopology(vanilla.getPrimitiveTopology())
                .withCull(!f.shadow && vanilla.isCull())
                .withPolygonMode(vanilla.getPolygonMode())
                .withDepthStencilState(f.shadow ? new com.mojang.renderpearl.api.pipeline.DepthStencilState(
                        com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN_OR_EQUAL, true) : vanilla.getDepthStencilState())
                .withVertexBinding(0, vanilla.getVertexFormatBinding(0));
        Optional<BlendFunction> vanillaBlend = vanilla.getColorTargetStates().getFirst().blendFunction();
        for (int i = 0; i < f.writes.size(); i++) {
            int index = f.writes.get(i);
            if (f.shadow) {
                b.withColorTargetState(i, new ColorTargetState(Optional.empty(), ShadowTargets.FORMAT, ColorTargetState.WRITE_ALL));
                continue;
            }
            Optional<BlendFunction> fallbackBlend = f.blendOverride != null ? f.blendOverride : vanillaBlend;
            Optional<BlendFunction> blend = TerrainTakeover.blend(f.properties, f.blendProgram != null ? f.blendProgram : f.program, index, fallbackBlend);
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
        return new Prepared(f, vanilla, p, b.build(),
                new LuxiumShaderSource().put(shader, ShaderType.VERTEX, r.vertex()).put(shader, ShaderType.FRAGMENT, r.fragment()),
                started, (System.nanoTime() - started) / 1_000_000);
    }

    /** Records a compiled pipeline (throws if the device returned none). */
    private static CompiledRenderPipeline done(Prepared prep, @org.jspecify.annotations.Nullable CompiledRenderPipeline c, String how) {
        if (c == null) {
            throw new IllegalStateException("the device returned no pipeline");
        }
        programOf.put(c, prep.program());
        compiledCount++;
        float alpha = prep.f().alphaFor(prep.vanilla());
        MetalShadersClient.LOGGER.info("Entity takeover: {} -> {} ({}), {} pipelines so far, {} ms since prepared ({})",
                prep.vanilla().getLocation(), prep.f().program, Float.isNaN(alpha) ? "no alpha test" : "alpha > " + alpha, compiledCount,
                (System.nanoTime() - prep.started()) / 1_000_000, how);
        return c;
    }

    // ---- Windows (from LevelRendererTerrainMixin), opened with the level's pass suspended -----

    private static boolean saidWindow;

    /** Frame start (from PackChain.beginFrame, no pass open): last frame's uniform buffers rotate. */
    /** Forgets every family's program (a new world folder is being loaded; see PackChain.unload). */
    static void unload() {
        for (Family f : ALL) {
            f.compiled.values().forEach(CompiledRenderPipeline::close);
            f.compiled.clear();
            f.refused.clear();
            f.byAlpha.values().forEach(Program::close);
            f.byAlpha.clear();
            f.inputs.clear();
            f.served = 0;
            f.glsl = null;
            f.blendProgram = null;
            f.writes = List.of();
            f.prepared = false;
        }
        programOf.clear();
        window = null;
        drawing = null;
        holdFamily = null;
        hold = null;
        inHold = false;
    }

    static void beginFrame() {
        for (Family f : ALL) {
            f.byAlpha.values().forEach(Program::rotate);
        }
        DrawIds.beginFrame();
    }

    /** Before the level's solid features. */
    public static void openWindow(Function<String, Object> values) {
        open(Phase.SOLID, values, List.of(SOLID, BLOCK, PARTICLES, SPIDEREYES, BEACON, BEACON_TRANSLUCENT, LIGHTNING,
                BLOCK_TRANSLUCENT, ENTITY_PATTERNS, TEXT, TEXT_BE));
    }

    /** Before the level's translucent features, and its "after terrain" ones (after the deferred cut). */
    public static void openTranslucentWindow(Function<String, Object> values) {
        open(Phase.TRANSLUCENT, values, List.of(TRANSLUCENT, PARTICLES_TRANSLUCENT, SPIDEREYES, BEACON, BEACON_TRANSLUCENT, LIGHTNING,
                TEXT, TEXT_BE));
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
                        f.inputs.put(s.name(), PackChain.inputView(s.name(), p.result().samplers()));
                    }
                }
            }
        }
    }

    public static void closeWindow() {
        try {
            releaseHold();
        } finally {
            window = null;
            handProjection = null;
            holdDepth = null;
            particleFamily = null;
            drawing = null;
            drawingIds = null;
            inHold = false;
        }
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
        if (hold != null && holdFamily != f && (!holdFamily.writes.equals(f.writes) || holdFamily.coverage() != f.coverage()
                || holdFamily.shadow != f.shadow)) {
            releaseHold();   // another program with other attachments: another pass
        }
        if (hold == null) {
            holdFamily = f;
            RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(() -> "Luxium entities (pack " + f.program + ")");
            for (int index : f.writes) {
                d.withColorAttachment(f.shadow ? ShadowTargets.color(index) : PackChain.gbufferTarget(index));
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
        RenderPass h = hold;
        hold = null;   // forgotten first: a pass whose close throws must not be closed again
        if (h != null) {
            h.close();
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
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            GpuTextureView v = f.inputs.get(s.name());
            if (v != null) {
                pass.setUniform(s.name(), v, CustomTextures.samplerFor(s.name(), v));
            }
        }
        if (p.layout().size() > 0) {
            // A buffer per draw: the ids differ from draw to draw. Mapping is CPU work, so it's
            // fine inside the pass; rotating isn't, and happens at frame start. The buffers are the
            // variant's own, sized for its block (variants of one program differ in size).
            MappableRingBuffer ring = p.nextRing();
            DrawIds.Ids ids = drawingIds;
            Function<String, Object> values = frameValues;
            int stage = f.stage;
            Function<String, Object> perDraw = name -> switch (name) {
                case "entityId" -> ids == null ? 0 : ids.entityId();
                case "blockEntityId" -> ids == null ? 0 : ids.blockEntityId();
                case "currentRenderedItemId" -> ids == null ? 0 : ids.itemId();
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
        boolean shadowPass = window == Phase.SHADOW;
        Matrix4f projection = handProjection != null ? new Matrix4f(handProjection)
                : values.apply(shadowPass ? "shadowProjection" : "gbufferProjection") instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
        // The draw's pose is camera-relative under the camera's view rotation; seen from the light
        // it's shadowModelView * inverse(view) * that.
        Matrix4f shadowFix = new Matrix4f();
        if (shadowPass && values.apply("shadowModelView") instanceof Matrix4fc smv) {
            shadowFix = new Matrix4f(smv).mul(new Matrix4f(modelView).invert());
        }
        Matrix4f fix = shadowFix;
        Matrix4fc[] textureMatrices = new Matrix4fc[8];
        Arrays.fill(textureMatrices, new Matrix4f());
        textureMatrices[1] = LIGHTMAP_MATRIX;
        // Iris IrisExclusiveUniforms glintAlpha: the glint strength option (the game's GlintAlpha).
        float glintAlpha = Minecraft.getInstance().options.glintStrength().get().floatValue();
        return name -> switch (name) {
            case ProgramTranslator.GLINT_ALPHA -> glintAlpha;
            case "luxium_ModelViewMatrix" -> modelView;
            case "luxium_ProjectionMatrix" -> projection;
            case ProgramTranslator.SHADOW_FIX -> fix;
            case "luxium_TextureMatrix" -> textureMatrices;
            default -> values.apply(name);
        };
    }
}
