package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
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
                           Map<String, String> defaults, CompiledRenderPipeline compiled, List<MappableRingBuffer> rings, int stage) {
    }

    /** Albedo sampler names a pack may read the block atlas under (Iris: gtexture, texture, tex). */
    private static final Set<String> ALBEDO = Set.of("gtexture", "texture", "tex", "gcolor");
    /** Iris's lightmap texture matrix (BuiltinReplacementUniforms): 1/256 scale, half a texel of 16 in. */
    private static final Matrix4f LIGHTMAP_MATRIX = new Matrix4f(
            0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0.03125f, 0.03125f, 0.03125f, 1);

    private static final Map<TerrainRenderPass, Program> programs = new IdentityHashMap<>();
    private static final Map<String, GpuTextureView> inputs = new HashMap<>();
    private static List<Integer> drawBuffers = List.of();
    private static boolean prepared;
    private static GpuSampler atlasSampler;

    // Per-frame and per-pass state.
    private static ChunkSectionsToRender sections;
    private static boolean drewOpaque;
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

    /** Compiles the pack's terrain program for Sodium's solid and cutout passes. */
    public static void prepare(String[] terrain) {
        try {
            // Iris ShaderKey: SODIUM_TERRAIN_SOLID is AlphaTests.OFF, SODIUM_TERRAIN_CUTOUT is HALF_ALPHA.
            add(DefaultTerrainRenderPasses.SOLID, "solid", terrain, Float.NaN, 8);        // WorldRenderingPhase.TERRAIN_SOLID
            add(DefaultTerrainRenderPasses.CUTOUT, "cutout", terrain, 0.5f, 10);         // WorldRenderingPhase.TERRAIN_CUTOUT
            atlasSampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
            prepared = true;
            MetalShadersClient.LOGGER.info("Terrain takeover ready: gbuffers_terrain writes colortex{} plus the coverage mask",
                    drawBuffers);
        } catch (Exception e) {
            programs.clear();
            MetalShadersClient.LOGGER.error("Terrain takeover disabled: {}", e.getMessage(), e);
        }
    }

    private static void add(TerrainRenderPass pass, String name, String[] glsl, float alphaTest, int stage) throws Exception {
        ProgramTranslator.Result r = ProgramTranslator.translateForTerrain(glsl[0], glsl[1], new ProgramTranslator.Terrain(alphaTest));
        List<Integer> writes = r.drawBuffers().isEmpty() ? List.of(0) : r.drawBuffers();
        int coverageAt = r.fragmentOutputs().stream().filter(s -> s.name().equals(ProgramTranslator.COVERAGE_OUTPUT))
                .findFirst().orElseThrow().location();
        if (coverageAt != writes.size()) {
            throw new IllegalStateException("gbuffers_terrain writes outputs past its draw buffers " + writes);
        }
        PackChain.requireTargets(writes);
        drawBuffers = writes;
        Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/gbuffers_terrain_" + name);
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/terrain/" + name))
                .withVertexShader(shader).withFragmentShader(shader)
                // Sodium's chunk pipeline state (ShaderChunkRenderer.createShader, 0.9.2).
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(true)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withPushConstantSize(ProgramTranslator.SODIUM_PUSH_CONSTANT_SIZE)
                .withVertexBinding(0, LuxiumChunkVertex.VERTEX_FORMAT);
        for (int i = 0; i < writes.size(); i++) {
            int index = writes.get(i);
            b.withColorTargetState(i, new ColorTargetState(Optional.empty(), PackChain.targetFormat(index),
                    PackChain.promoted(index) ? ColorTargetState.WRITE_COLOR : ColorTargetState.WRITE_ALL));
        }
        b.withColorTargetState(writes.size(), new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL));
        BindGroupLayout.Builder bindings = BindGroupLayout.builder()
                .withUniform(ProgramTranslator.SODIUM_GLOBALS, UniformType.UNIFORM_BUFFER);
        if (!r.uniforms().isEmpty()) {
            bindings.withUniform(ProgramTranslator.UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
        }
        for (ProgramTranslator.Sampler s : r.samplers()) {
            bindings.withUniform(s.name(), UniformType.COMBINED_IMAGE_SAMPLER);
        }
        b.withBindGroupLayout(bindings.build());
        CompiledRenderPipeline compiled = RenderSystem.getDevice().compilePipeline(b.build(),
                new LuxiumShaderSource().put(shader, ShaderType.VERTEX, r.vertex()).put(shader, ShaderType.FRAGMENT, r.fragment()),
                Runnable::run).join().finishCompile();
        if (compiled == null) {
            throw new IllegalStateException("gbuffers_terrain (" + name + "): the device returned no pipeline");
        }
        Std140Layout.Layout layout = Std140Layout.of(r.uniforms());
        Map<String, String> defaults = new HashMap<>();
        r.uniforms().forEach(u -> {
            if (u.defaultValue() != null) {
                defaults.put(u.name(), u.defaultValue());
            }
        });
        programs.put(pass, new Program(pass, r, layout, defaults, compiled, new ArrayList<>(), stage));
        MetalShadersClient.LOGGER.info("Terrain {}: inputs {}, samplers {}", name,
                r.vertexInputs().stream().map(ProgramTranslator.Slot::name).toList(),
                r.samplers().stream().map(ProgramTranslator.Sampler::name).toList());
    }

    /** Frame start (from PackChain.beginFrame, no pass open): resolve inputs, reset per-frame state. */
    static void beginFrame() {
        usedRings.forEach(MappableRingBuffer::rotate);
        usedRings.clear();
        drewOpaque = false;
        ringSlot = 0;
        if (!active()) {
            return;
        }
        for (Program p : programs.values()) {
            for (ProgramTranslator.Sampler s : p.result().samplers()) {
                if (!ALBEDO.contains(s.name())) {
                    inputs.put(s.name(), PackChain.inputView(s.name()));
                }
            }
        }
    }

    // ---- Called from LevelRendererTerrainMixin -------------------------------------------------

    public static void noteSections(ChunkSectionsToRender chunkSections) {
        sections = chunkSections;
    }

    /**
     * Just before the game opens its "Main" pass: draws Sodium's opaque terrain (solid, then
     * cutout) into a pass on the pack's targets, sharing the game's depth.
     */
    public static void drawOpaque(GpuTextureView depth, GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        // Only Sodium's sections: the game's own would draw vanilla pipelines into the pack's targets.
        if (!active() || !(sections instanceof SodiumChunkSection) || wireframe || depth == null) {
            return;
        }
        RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(() -> "Luxium terrain (pack gbuffers_terrain)");
        for (int index : drawBuffers) {
            GpuTextureView view = PackChain.gbufferTarget(index);
            if (view == null) {
                return;   // the chain's buffers aren't allocated yet (first frame)
            }
            d.withColorAttachment(view);
        }
        d.withColorAttachment(PackChain.coverageTarget());
        d.withDepthAttachment(depth);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(d.build())) {
            inPass = true;
            sections.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
        } finally {
            inPass = false;
            currentPass = null;
        }
        drewOpaque = true;
        if (System.nanoTime() - logged > 5_000_000_000L) {
            logged = System.nanoTime();
            MetalShadersClient.LOGGER.info("Terrain drawn by the pack: {} of Sodium's passes this frame (solid, cutout)", ringSlot);
        }
    }

    /** Whether the game's own opaque terrain draw into "Main" should be skipped this frame. */
    public static boolean drewOpaque() {
        return drewOpaque;
    }

    // ---- Called from DefaultChunkRendererMixin -------------------------------------------------

    public static void enterTerrainPass(TerrainRenderPass pass) {
        currentPass = inPass ? pass : null;
    }

    /** The pack's pipeline for the terrain pass being drawn, or Sodium's own. */
    public static CompiledRenderPipeline pipelineFor(CompiledRenderPipeline original) {
        Program p = currentPass == null ? null : programs.get(currentPass);
        return p != null ? p.compiled() : original;
    }

    /** After Sodium sets the pipeline: the pack's uniform block and textures. */
    public static void bind(RenderPass pass, Function<String, Object> values) {
        Program p = currentPass == null ? null : programs.get(currentPass);
        if (p == null) {
            return;
        }
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuTextureView atlas = currentPass.getAtlas();
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            if (ALBEDO.contains(s.name())) {
                pass.setUniform(s.name(), atlas, atlasSampler);
                continue;
            }
            GpuTextureView v = inputs.get(s.name());
            if (v != null) {
                boolean depthLike = s.name().startsWith("depthtex") || s.name().startsWith("shadowtex");
                pass.setUniform(s.name(), v, depthLike ? nearest : linear);
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
        Matrix4f modelView = values.apply("gbufferModelView") instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
        Matrix4f projection = values.apply("gbufferProjection") instanceof Matrix4fc m ? new Matrix4f(m) : new Matrix4f();
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
