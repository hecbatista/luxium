package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.glsl.ProgramTranslator;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import io.github.hecbatista.metalshaders.sodium.LuxiumChunkVertex;
import io.github.hecbatista.metalshaders.uniform.UniformWriter;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

/**
 * Step 6d-2: solid and cutout terrain drawn by the pack's gbuffers_terrain, through Sodium.
 * <p>
 * Minecraft 26.3 draws the opaque terrain first inside one "Main" render pass on its own colour
 * target (LevelRenderer.addMainPass -> executeSolid). The pack's program writes several targets, so
 * the terrain gets a pass of its own, opened just before the game opens "Main": the pack's
 * DRAWBUFFERS targets (current halves), the coverage mask as one more attachment, and the game's
 * depth, so entities drawn afterwards still test against the terrain. Sodium draws into that
 * pass with everything it normally does (culling, regions, draw commands, push constants); only
 * the pipeline is swapped for the pack's, per terrain pass, and the pack's uniform block and
 * textures are bound after Sodium sets the pipeline. The game's own call that would have drawn the
 * opaque terrain into "Main" is then skipped for the frame.
 * <p>
 * Follows Vitrail's notes (docs/internals/terrain.md): one program per terrain pass (solid and
 * cutout, so flush surfaces resolve the same depth), the alpha test from the pass (Iris: solid
 * OFF, cutout HALF_ALPHA), the atlas read NEAREST as under Iris.
 */
public final class TerrainTakeover {
    private record Program(TerrainRenderPass pass, ProgramTranslator.Result result, Std140Layout.Layout layout,
                           Map<String, String> defaults, CompiledRenderPipeline compiled, List<MappableRingBuffer> rings, int stage,
                           List<Integer> writes, boolean coverage) {
    }

    /** Albedo sampler names a pack may read the block atlas under (Iris: gtexture, texture, tex). */
    private static final Set<String> ALBEDO = Set.of("gtexture", "texture", "tex", "gcolor");
    /** Iris's lightmap texture matrix (BuiltinReplacementUniforms): 1/256 scale, half a texel of 16 in. */
    private static final Matrix4f LIGHTMAP_MATRIX = new Matrix4f(
            0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0.03125f, 0.03125f, 0.03125f, 1);

    private static final Map<TerrainRenderPass, Program> programs = new IdentityHashMap<>();
    /** The pack's shadow programs for the solid and cutout passes (shadow.vsh/fsh, step 6e). */
    private static final Map<TerrainRenderPass, Program> shadowPrograms = new IdentityHashMap<>();
    /** True while the shadow map is being drawn: the shadow programs and the light's matrices. */
    private static boolean shadowDrawing;
    private static final Map<String, GpuTextureView> inputs = new HashMap<>();
    private static boolean prepared;
    private static GpuSampler atlasSampler;

    // Per-frame and per-pass state.
    private static ChunkSectionsToRender sections;
    private static boolean drewOpaque;
    private static boolean drewTranslucent;
    private static boolean inPass;
    private static TerrainRenderPass currentPass;
    private static int ringSlot;
    private static final List<MappableRingBuffer> usedRings = new ArrayList<>();
    private static long logged;

    private TerrainTakeover() {
    }

    /** Debug: LUXIUM_NO_TERRAIN=1 leaves the terrain to Sodium's own shader, for before/after shots. */
    private static final boolean SWITCHED_OFF = System.getenv("LUXIUM_NO_TERRAIN") != null;

    public static boolean active() {
        return prepared && !SWITCHED_OFF && PackChain.ready();
    }

    /**
     * Compiles the pack's terrain program for Sodium's solid and cutout passes, and its water
     * program (if any) for the translucent pass. {@code properties} is the preprocessed
     * shaders.properties, for the {@code alphaTest.*} and {@code blend.*} directives.
     */
    public static void prepare(String[] terrain, String[] water, java.util.Properties properties) {
        // Step 7g: the three compiles are started together on the pool, then finished in order.
        Started solid = null;
        Started cutout = null;
        try {
            // Iris ShaderKey: SODIUM_TERRAIN_SOLID is AlphaTests.OFF, SODIUM_TERRAIN_CUTOUT is HALF_ALPHA.
            solid = start(DefaultTerrainRenderPasses.SOLID, "gbuffers_terrain", "solid", terrain, Float.NaN, 8, true, properties, false);   // TERRAIN_SOLID
            cutout = start(DefaultTerrainRenderPasses.CUTOUT, "gbuffers_terrain", "cutout", terrain, 0.5f, 10, true, properties, false);   // TERRAIN_CUTOUT
        } catch (Exception e) {
            discard(solid);
            MetalShadersClient.LOGGER.error("Terrain takeover disabled: {}", e.getMessage(), e);
            return;
        }
        Started translucent = null;
        Exception translucentError = null;
        if (water != null) {
            try {
                // Iris ShaderKey: SODIUM_TERRAIN_TRANSLUCENT is ONE_TENTH_ALPHA unless the pack says otherwise.
                float alpha = alphaTest(properties, "gbuffers_water", 0.1f);
                translucent = start(DefaultTerrainRenderPasses.TRANSLUCENT, "gbuffers_water", "translucent", water, alpha, 17, false, properties, false); // TERRAIN_TRANSLUCENT
            } catch (Exception e) {
                translucentError = e;
            }
        }
        try {
            finish(solid);
            solid = null;
            finish(cutout);
            cutout = null;
            atlasSampler = atlasSampler != null ? atlasSampler : RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
            prepared = true;
            MetalShadersClient.LOGGER.info("Terrain takeover ready: gbuffers_terrain writes colortex{} plus the coverage mask",
                    programs.get(DefaultTerrainRenderPasses.SOLID).writes());
        } catch (Exception e) {
            discard(solid, cutout, translucent);
            programs.values().forEach(p -> p.compiled().close());
            programs.clear();
            MetalShadersClient.LOGGER.error("Terrain takeover disabled: {}", e.getMessage(), e);
            return;
        }
        if (water == null) {
            MetalShadersClient.LOGGER.warn("Translucent terrain stays the game's: the pack has no gbuffers_water");
            return;
        }
        try {
            if (translucentError != null) {
                throw translucentError;
            }
            finish(translucent);
        } catch (Exception e) {
            programs.remove(DefaultTerrainRenderPasses.TRANSLUCENT);
            MetalShadersClient.LOGGER.error("Translucent terrain stays the game's: {}", e.getMessage(), e);
        }
    }

    /**
     * The pack's shadow program for Sodium's solid and cutout passes (Iris ShaderKey
     * SHADOW_SODIUM_TERRAIN_SOLID off, _CUTOUT half), drawn into the shadow map before the terrain.
     */
    public static void prepareShadow(String[] shadow, java.util.Properties properties) {
        if (!prepared) {
            return;
        }
        try {
            // Step 7g: started together on the pool, then finished in order.
            Started[] started = new Started[3];
            try {
                started[0] = start(DefaultTerrainRenderPasses.SOLID, "shadow", "shadow_solid", shadow, Float.NaN, 8, false, properties, true);
                started[1] = start(DefaultTerrainRenderPasses.CUTOUT, "shadow", "shadow_cutout", shadow, 0.5f, 10, false, properties, true);
                // Iris ShaderKey SHADOW_SODIUM_TERRAIN_TRANSLUCENT (ProgramId.ShadowWater -> Shadow): one tenth.
                started[2] = start(DefaultTerrainRenderPasses.TRANSLUCENT, "shadow", "shadow_translucent", shadow, 0.1f, 17, false, properties, true);
                for (int i = 0; i < started.length; i++) {
                    finish(started[i]);
                    started[i] = null;
                }
            } catch (Exception e) {
                discard(started);
                throw e;
            }
            MetalShadersClient.LOGGER.info("Shadow pass ready: shadow writes shadowcolor{} at {}x{}",
                    shadowPrograms.get(DefaultTerrainRenderPasses.SOLID).writes(),
                    io.github.hecbatista.metalshaders.uniform.ShadowMatrices.directives().resolution(),
                    io.github.hecbatista.metalshaders.uniform.ShadowMatrices.directives().resolution());
        } catch (Exception e) {
            // Variants that finished before the failure own pipelines (audit S4-03).
            for (Program p : shadowPrograms.values()) {
                p.compiled().close();
                p.rings().forEach(MappableRingBuffer::close);
            }
            shadowPrograms.clear();
            MetalShadersClient.LOGGER.error("Shadow pass disabled: {}", e.getMessage(), e);
        }
    }

    public static boolean shadowActive() {
        return active() && !shadowPrograms.isEmpty();
    }

    /**
     * The shadow map, drawn just before the opaque terrain from the light's own Sodium render list
     * (ShadowCulling, step 7c). Cleared every frame: depth to 1, colours to white (Iris's
     * shadowcolor defaults).
     */
    public static void drawShadow(GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        long luxium$t0 = PerfStats.start();
        try {
            drawShadowTimed(sampler, atlas, wireframe);
        } finally {
            PerfStats.end(PerfStats.Section.SHADOW, luxium$t0);
        }
    }

    private static void drawShadowTimed(GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        Program p = shadowPrograms.get(DefaultTerrainRenderPasses.SOLID);
        if (!shadowActive() || p == null || !(sections instanceof SodiumChunkSection) || wireframe) {
            return;
        }
        ShadowTargets.ensure(io.github.hecbatista.metalshaders.uniform.ShadowMatrices.directives().resolution(), p.writes());
        ShadowTargets.clear();
        shadowDrawing = true;
        try {
            resolveInputs(p);
            // Step 7c: the light's own render list, so casters the camera can't see still cast.
            ShadowCulling.withLightLists(() -> drawShadowTerrain(p, sampler, atlas));
        } finally {
            shadowDrawing = false;
        }
        ShadowTargets.publish();
    }

    private static void drawShadowTerrain(Program p, GpuSampler sampler, GpuTextureView atlas) {
        {
            RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(() -> "Luxium shadow map (pack shadow)");
            for (int index : p.writes()) {
                d.withColorAttachment(ShadowTargets.color(index));
            }
            d.withDepthAttachment(ShadowTargets.depthAttachment());
            RenderPassDescriptor descriptor = d.build();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
                inPass = true;
                sections.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
            } finally {
                inPass = false;
                currentPass = null;
            }
            // Entities and block entities cast shadows too: the level's solid features replayed.
            EntityTakeover.drawShadowCasters(featureFrame, MetalShadersClient::value);
            // shadowtex1 is the map without translucents: a copy taken between the two halves
            // (Vitrail sky-and-shadows.md "Two maps, one render"); shadowtex0 then has them too.
            ShadowTargets.publishOpaque();
            if (shadowPrograms.containsKey(DefaultTerrainRenderPasses.TRANSLUCENT)) {
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
                    inPass = true;
                    sections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
                } finally {
                    inPass = false;
                    currentPass = null;
                }
            }
        }
    }

    /**
     * {@code alphaTest.<program>=GREATER 0.1} (Iris AlphaTest syntax); only GREATER and off are read.
     * "off" and "false" both turn the test off, as Iris's ShaderProperties reads them (Bliss writes
     * {@code alphaTest.gbuffers_water=false}: its near water has alpha 1/255).
     */
    static float alphaTest(java.util.Properties properties, String program, float fallback) {
        String v = properties.getProperty("alphaTest." + program);
        if (v == null) {
            return fallback;
        }
        String[] parts = v.strip().split("\\s+");
        if (parts[0].equalsIgnoreCase("off") || parts[0].equalsIgnoreCase("false")) {
            return Float.NaN;
        }
        if (parts.length == 2 && parts[0].equals("GREATER")) {
            return Float.parseFloat(parts[1]);
        }
        MetalShadersClient.LOGGER.warn("alphaTest.{}={} isn't supported; using > {}", program, v, fallback);
        return fallback;
    }

    /**
     * The blend for one draw buffer of a program (OptiFine/Iris {@code blend.<program>} and
     * {@code blend.<program>.<buffer>}: "off" or four factors src dst srcAlpha dstAlpha), else
     * {@code fallback}. The factor names are the same as the game's BlendFactor names.
     */
    static Optional<BlendFunction> blend(java.util.Properties properties, String program, int buffer, Optional<BlendFunction> fallback) {
        String v = null;
        for (String name : List.of("colortex" + buffer, LEGACY_NAMES.getOrDefault(buffer, ""))) {
            if (!name.isEmpty() && v == null) {
                v = properties.getProperty("blend." + program + "." + name);
            }
        }
        if (v == null) {
            v = properties.getProperty("blend." + program);
        }
        if (v == null) {
            return fallback;
        }
        String[] f = v.strip().split("\\s+");
        if (f.length == 1 && f[0].equalsIgnoreCase("off")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlendFunction(BlendFactor.valueOf(f[0]), BlendFactor.valueOf(f[1]),
                    BlendFactor.valueOf(f[2]), BlendFactor.valueOf(f[3])));
        } catch (RuntimeException e) {
            MetalShadersClient.LOGGER.warn("blend.{} = {} isn't understood; ignored", program, v);
            return fallback;
        }
    }

    private static final Map<Integer, String> LEGACY_NAMES = Map.of(
            0, "gcolor", 1, "gdepth", 2, "gnormal", 3, "composite", 4, "gaux1", 5, "gaux2", 6, "gaux3", 7, "gaux4");

    /** A terrain pipeline whose compile was started on the pool (step 7g); {@link #finish} records it. */
    private record Started(TerrainRenderPass pass, String program, String name, float alphaTest, int stage, boolean coverage,
                           boolean shadow, ProgramTranslator.Result result, List<Integer> writes, List<String> blends,
                           java.util.concurrent.CompletableFuture<CompiledRenderPipeline.Pending> compile) {
    }

    /** Translates and builds one terrain pipeline, and starts its compile on {@link PipelineCompiler}'s pool. */
    private static Started start(TerrainRenderPass pass, String program, String name, String[] glsl, float alphaTest, int stage,
                                 boolean coverage, java.util.Properties properties, boolean shadow) throws Exception {
        ProgramTranslator.Result r = ProgramTranslator.translateForTerrain(PackChain.gbufferVolumes(glsl[0]), PackChain.gbufferVolumes(glsl[1]), new ProgramTranslator.Terrain(alphaTest, coverage, shadow));
        List<Integer> writes = r.drawBuffers().isEmpty() ? List.of(0) : r.drawBuffers();
        if (coverage) {
            int coverageAt = r.fragmentOutputs().stream().filter(s -> s.name().equals(ProgramTranslator.COVERAGE_OUTPUT))
                    .findFirst().orElseThrow().location();
            if (coverageAt != writes.size()) {
                throw new IllegalStateException(program + " writes outputs past its draw buffers " + writes);
            }
        }
        if (!shadow) {
            PackChain.requireTargets(writes);
        }
        // Iris leaves gbuffers_water on the vanilla translucent blend; terrain draws unblended.
        // (Iris ProgramId.Shadow*/ShadowWater: BlendModeOverride.OFF, so the shadow pass is never blended by default.)
        Optional<BlendFunction> defaultBlend = pass.isTranslucent() && !shadow ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty();
        Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/" + program + "_" + name);
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/terrain/" + name))
                .withVertexShader(shader).withFragmentShader(shader)
                // Sodium's chunk pipeline state (ShaderChunkRenderer.createShader, 0.9.2).
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                // The shadow pass draws both sides and keeps forward depth (cleared to 1, less-or-equal).
                .withCull(!shadow)
                .withDepthStencilState(shadow ? new DepthStencilState(com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN_OR_EQUAL, true)
                        : DepthStencilState.DEFAULT)
                .withPushConstantSize(ProgramTranslator.SODIUM_PUSH_CONSTANT_SIZE)
                .withVertexBinding(0, LuxiumChunkVertex.VERTEX_FORMAT);
        List<String> blends = new ArrayList<>();
        for (int i = 0; i < writes.size(); i++) {
            int index = writes.get(i);
            Optional<BlendFunction> blend = blend(properties, program, index, defaultBlend);
            blends.add("colortex" + index + (blend.isPresent() ? " blended" : " unblended"));
            b.withColorTargetState(i, new ColorTargetState(blend, shadow ? ShadowTargets.FORMAT : PackChain.targetFormat(index),
                    PackChain.promoted(index) ? ColorTargetState.WRITE_COLOR : ColorTargetState.WRITE_ALL));
        }
        if (coverage) {
            b.withColorTargetState(writes.size(), new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL));
        }
        BindGroupLayout.Builder bindings = BindGroupLayout.builder()
                .withUniform(ProgramTranslator.SODIUM_GLOBALS, UniformType.UNIFORM_BUFFER);
        if (!r.uniforms().isEmpty()) {
            bindings.withUniform(ProgramTranslator.UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
        }
        for (ProgramTranslator.Sampler s : r.samplers()) {
            bindings.withUniform(s.name(), UniformType.COMBINED_IMAGE_SAMPLER);
        }
        b.withBindGroupLayout(bindings.build());
        return new Started(pass, program, name, alphaTest, stage, coverage, shadow, r, writes, blends, PipelineCompiler.start(b.build(),
                new LuxiumShaderSource().put(shader, ShaderType.VERTEX, r.vertex()).put(shader, ShaderType.FRAGMENT, r.fragment())));
    }

    /** Finishes a started terrain pipeline (render thread) and records it. */
    private static void finish(Started st) {
        TerrainRenderPass pass = st.pass();
        String program = st.program();
        String name = st.name();
        ProgramTranslator.Result r = st.result();
        List<Integer> writes = st.writes();
        CompiledRenderPipeline compiled = PipelineCompiler.finish(st.compile());
        if (compiled == null) {
            throw new IllegalStateException(program + " (" + name + "): the device returned no pipeline");
        }
        Std140Layout.Layout layout;
        try {
            layout = Std140Layout.of(r.uniforms());
        } catch (RuntimeException e) {
            compiled.close();
            throw e;
        }
        Map<String, String> defaults = new HashMap<>();
        r.uniforms().forEach(u -> {
            if (u.defaultValue() != null) {
                defaults.put(u.name(), u.defaultValue());
            }
        });
        (st.shadow() ? shadowPrograms : programs).put(pass, new Program(pass, r, layout, defaults, compiled, new ArrayList<>(), st.stage(), writes, st.coverage()));
        MetalShadersClient.LOGGER.info("Terrain {} ({}): inputs {}, samplers {}, alpha test {}, {}", name, program,
                r.vertexInputs().stream().map(ProgramTranslator.Slot::name).toList(),
                r.samplers().stream().map(ProgramTranslator.Sampler::name).toList(),
                Float.isNaN(st.alphaTest()) ? "off" : "> " + st.alphaTest(), st.blends());
    }

    /** Finishes and closes started compiles that won't be used (their takeover failed). */
    private static void discard(Started... started) {
        for (Started st : started) {
            if (st == null) {
                continue;
            }
            try {
                CompiledRenderPipeline c = PipelineCompiler.finish(st.compile());
                if (c != null) {
                    c.close();
                }
            } catch (RuntimeException ignored) {
                // its own failure; the takeover's is reported already
            }
        }
    }

    /** Forgets the pack's terrain and shadow programs (a new world folder is being loaded; see PackChain.unload). */
    static void unload() {
        for (Map<TerrainRenderPass, Program> map : List.of(programs, shadowPrograms)) {
            for (Program p : map.values()) {
                p.compiled().close();
                p.rings().forEach(MappableRingBuffer::close);
            }
            map.clear();
        }
        inputs.clear();
        usedRings.clear();
        prepared = false;
        shadowDrawing = false;
        drewOpaque = false;
        drewTranslucent = false;
        inPass = false;
        currentPass = null;
    }

    /** Frame start (from PackChain.beginFrame, no pass open): resolve inputs, reset per-frame state. */
    /**
     * Forgets this frame's "the pack drew it" flags. Runs at every frame start, even when the chain
     * has failed: a stale flag would make the level's wrappers skip vanilla terrain that nothing
     * else draws (audit S4-01).
     */
    static void resetDrawFlags() {
        drewOpaque = false;
        drewTranslucent = false;
    }

    static void beginFrame() {
        usedRings.forEach(MappableRingBuffer::rotate);
        usedRings.clear();
        drewOpaque = false;
        drewTranslucent = false;
        ringSlot = 0;
    }

    /**
     * What each sampler of {@code p} reads, looked up right before its pass opens (placeholders
     * may be created, which can't happen inside a pass). Not at frame start: the deferred passes
     * flip buffers between the opaque and the translucent terrain, and the water must read the
     * halves they just wrote (Complementary's gbuffers_water discards where gaux2's cloud depth,
     * written by deferred1, is nearer; a stale half made it discard everything).
     */
    private static void resolveInputs(Program p) {
        inputs.clear();
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            if (!ALBEDO.contains(s.name())) {
                inputs.put(s.name(), PackChain.inputView(s.name(), p.result().samplers()));
            }
        }
    }

    // ---- Called from LevelRendererTerrainMixin -------------------------------------------------

    public static void noteSections(ChunkSectionsToRender chunkSections) {
        sections = chunkSections;
    }

    private static net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame featureFrame;

    /** The level's prepared features for this frame (replayed into the shadow map). */
    public static void noteFeatureFrame(net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame frame) {
        featureFrame = frame;
    }

    /**
     * Just before the game opens its "Main" pass: draws Sodium's opaque terrain (solid, then
     * cutout) into a pass on the pack's targets plus the coverage mask, sharing the game's depth.
     */
    public static void drawOpaque(GpuTextureView depth, GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        long luxium$t0 = PerfStats.start();
        try {
            drawOpaqueTimed(depth, sampler, atlas, wireframe);
        } finally {
            PerfStats.end(PerfStats.Section.TERRAIN, luxium$t0);
        }
    }

    private static void drawOpaqueTimed(GpuTextureView depth, GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        PackChain.drawPrepare(net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget(), MetalShadersClient::value);
        drawShadow(sampler, atlas, wireframe);
        drewOpaque = drawGroup(ChunkSectionLayerGroup.OPAQUE, DefaultTerrainRenderPasses.SOLID, depth, sampler, atlas, wireframe);
    }

    /**
     * After the deferred passes (the chain's "after opaque" cut): draws Sodium's translucent
     * terrain with the pack's water program onto the halves the deferred passes wrote.
     */
    public static void drawTranslucent(GpuTextureView depth, GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        long luxium$t0 = PerfStats.start();
        try {
            drawTranslucentTimed(depth, sampler, atlas, wireframe);
        } finally {
            PerfStats.end(PerfStats.Section.TRANSLUCENT, luxium$t0);
        }
    }

    private static void drawTranslucentTimed(GpuTextureView depth, GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        drewTranslucent = drawGroup(ChunkSectionLayerGroup.TRANSLUCENT, DefaultTerrainRenderPasses.TRANSLUCENT, depth, sampler, atlas, wireframe);
    }

    private static boolean drawGroup(ChunkSectionLayerGroup group, TerrainRenderPass first, GpuTextureView depth,
                                     GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        Program p = programs.get(first);
        // Only Sodium's sections: the game's own would draw vanilla pipelines into the pack's targets.
        if (!active() || p == null || !(sections instanceof SodiumChunkSection) || wireframe || depth == null) {
            return false;
        }
        resolveInputs(p);
        RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(() -> "Luxium terrain " + group.label() + " (pack)");
        for (int index : p.writes()) {
            GpuTextureView view = PackChain.gbufferTarget(index);
            if (view == null) {
                return false;   // the chain's buffers aren't allocated yet (first frame)
            }
            d.withColorAttachment(view);
        }
        if (p.coverage()) {
            d.withColorAttachment(PackChain.coverageTarget());
        }
        d.withDepthAttachment(depth);
        int before = ringSlot;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(d.build())) {
            inPass = true;
            sections.renderGroup(group, pass, sampler, atlas, false);
        } finally {
            inPass = false;
            currentPass = null;
        }
        if (group == ChunkSectionLayerGroup.TRANSLUCENT && System.nanoTime() - logged > 5_000_000_000L) {
            logged = System.nanoTime();
            MetalShadersClient.LOGGER.info("Terrain drawn by the pack this frame: {} opaque and {} translucent Sodium passes",
                    before, ringSlot - before);
        }
        return true;
    }

    /** Whether the game's own opaque terrain draw into "Main" should be skipped this frame. */
    public static boolean drewOpaque() {
        return drewOpaque;
    }

    /** Whether the game's own translucent terrain draw should be skipped this frame. */
    public static boolean drewTranslucent() {
        return drewTranslucent;
    }

    // ---- Called from DefaultChunkRendererMixin -------------------------------------------------

    public static void enterTerrainPass(TerrainRenderPass pass) {
        currentPass = inPass ? pass : null;
    }

    /** The pack's pipeline for the terrain pass being drawn, or Sodium's own. */
    public static CompiledRenderPipeline pipelineFor(CompiledRenderPipeline original) {
        Program p = currentPass == null ? null : (shadowDrawing ? shadowPrograms : programs).get(currentPass);
        return p != null ? p.compiled() : original;
    }

    /** After Sodium sets the pipeline: the pack's uniform block and textures. */
    public static void bind(RenderPass pass, Function<String, Object> values) {
        Program p = currentPass == null ? null : (shadowDrawing ? shadowPrograms : programs).get(currentPass);
        if (p == null) {
            return;
        }
        GpuTextureView atlas = currentPass.getAtlas();
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            if (ALBEDO.contains(s.name())) {
                pass.setUniform(s.name(), atlas, atlasSampler);
                continue;
            }
            GpuTextureView v = inputs.get(s.name());
            if (v != null) {
                pass.setUniform(s.name(), v, CustomTextures.samplerFor(s.name(), v));
            }
        }
        if (p.layout().size() == 0) {
            return;
        }
        int slot = ringSlot++;
        while (p.rings().size() <= slot) {
            p.rings().add(new MappableRingBuffer(() -> "Luxium terrain uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, p.layout().size()));
        }
        MappableRingBuffer ring = p.rings().get(slot);
        try (GpuBufferSlice.MappedView view = ring.currentBuffer().map(false, true)) {
            UniformWriter.write(p.layout(), perDraw(values, p.stage(), atlas), p.defaults(), view.data());
        }
        pass.setUniform(ProgramTranslator.UNIFORM_BLOCK, ring.currentBuffer());
        usedRings.add(ring);
    }

    /** Terrain values over the frame's: the camera's matrices, the lightmap matrix, the stage. */
    private static Function<String, Object> perDraw(Function<String, Object> values, int stage, GpuTextureView atlas) {
        // In the shadow pass the fixed-function pair *is* the light's (Vitrail sky-and-shadows.md).
        String mv = shadowDrawing ? "shadowModelView" : "gbufferModelView";
        String pr = shadowDrawing ? "shadowProjection" : "gbufferProjection";
        Matrix4f modelView = values.apply(mv) instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
        Matrix4f projection = values.apply(pr) instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
        Matrix4f mvp = new Matrix4f(projection).mul(modelView);
        Matrix3f normal = new Matrix3f(modelView).invert().transpose();
        Matrix4fc[] textureMatrices = new Matrix4fc[8];
        Arrays.fill(textureMatrices, new Matrix4f());
        textureMatrices[1] = LIGHTMAP_MATRIX;
        int[] atlasSize = {atlas.getWidth(0), atlas.getHeight(0)};
        return name -> switch (name) {
            case "luxium_ModelViewMatrix" -> modelView;
            case "luxium_ProjectionMatrix" -> projection;
            case "luxium_ModelViewProjectionMatrix" -> mvp;
            case "luxium_NormalMatrix" -> normal;
            case "luxium_TextureMatrix" -> textureMatrices;
            case "renderStage" -> stage;
            case "atlasSize" -> atlasSize;
            default -> values.apply(name);
        };
    }
}
