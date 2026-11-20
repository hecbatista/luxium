package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
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
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Step 6d-1: the overworld sky drawn by the pack's gbuffers_skybasic (sky disc, sunrise band,
 * stars, dark disc) and gbuffers_skytextured (sun, moon), into colortex0, through vanilla's own
 * sky pass (SkyRenderer.render, hooked by SkyRendererMixin).
 * <p>
 * Following Vitrail's notes (docs/sky-and-shadows.md "The sky"): one compiled program per vanilla
 * pipeline, so each is read for exactly the vertex format it's drawn with; the pipeline's topology,
 * blending and depth state are copied from vanilla's (the sky has no depth state); each piece
 * gets its own model-view (the day rotation is the sun's position) and its renderStage. The End's
 * sky isn't taken over yet, so the takeover is off there ("all eight or none").
 */
public final class SkyTakeover {
    /** A pack program compiled for one vanilla sky pipeline. */
    private record Piece(RenderPipeline vanilla, String program, ProgramTranslator.Result result, Std140Layout.Layout layout,
                         Map<String, String> defaults, CompiledRenderPipeline compiled, List<MappableRingBuffer> rings) {
    }

    private static final Map<RenderPipeline, Piece> pieces = new IdentityHashMap<>();
    private static final Map<CompiledRenderPipeline, Piece> byCompiled = new IdentityHashMap<>();
    private static final Map<String, GpuTextureView> inputs = new HashMap<>();
    /** Albedo sampler names a pack may use for the bound texture (Iris: gtexture, texture, tex). */
    private static final Set<String> ALBEDO = Set.of("gtexture", "texture", "tex", "gcolor");
    private static boolean prepared;
    private static boolean overworldSky = true;

    // Per-draw state, filled from vanilla's calls inside the sky pass.
    private static boolean inSkyPass;
    private static Matrix4fc pendingModelView = new Matrix4f();
    private static Vector4f pendingColor = new Vector4f(1);
    private static String pendingLabel = "";
    private static int drawsThisFrame;
    private static final List<MappableRingBuffer> usedRings = new ArrayList<>();

    private SkyTakeover() {
    }

    /** True when the pack draws this frame's sky. */
    public static boolean active() {
        return prepared && overworldSky && PackChain.ready();
    }

    public static void setOverworldSky(boolean overworld) {
        overworldSky = overworld;
    }

    /** Compiles the pack's two sky programs once per vanilla sky pipeline. */
    public static void prepare(String[] skybasic, String[] skytextured) {
        try {
            add(RenderPipelines.SKY, "gbuffers_skybasic", skybasic);
            add(RenderPipelines.STARS, "gbuffers_skybasic", skybasic);
            add(RenderPipelines.SUNRISE_SUNSET, "gbuffers_skybasic", skybasic);
            add(RenderPipelines.CELESTIAL, "gbuffers_skytextured", skytextured);
            prepared = true;
            MetalShadersClient.LOGGER.info("Sky takeover ready: {} pieces", pieces.size());
        } catch (Exception e) {
            pieces.clear();
            byCompiled.clear();
            MetalShadersClient.LOGGER.error("Sky takeover disabled: {}", e.getMessage(), e);
        }
    }

    private static void add(RenderPipeline vanilla, String program, String[] glsl) throws Exception {
        Set<String> elements = new java.util.HashSet<>();
        vanilla.getVertexFormatBindings().getFirst().getElements().forEach(e -> elements.add(e.name()));
        ProgramTranslator.Result r = ProgramTranslator.translateForFormat(glsl[0], glsl[1], elements);
        List<Integer> writes = r.drawBuffers().isEmpty() ? List.of(0) : r.drawBuffers();
        if (!writes.equals(List.of(0))) {
            throw new IllegalStateException(program + " writes " + writes + "; only colortex0 is supported for the sky yet");
        }
        Identifier shader = Identifier.fromNamespaceAndPath("metalshaders", "pack/" + program + "_" + vanilla.getLocation().getPath().replace('/', '_'));
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metalshaders", "pipeline/sky/" + vanilla.getLocation().getPath()))
                .withVertexShader(shader).withFragmentShader(shader)
                .withPrimitiveTopology(vanilla.getPrimitiveTopology())
                .withVertexBinding(0, vanilla.getVertexFormatBindings().getFirst());
        ColorTargetState vanillaTarget = vanilla.getColorTargetStates().getFirst();
        b.withColorTargetState(new ColorTargetState(vanillaTarget.blendFunction(), PackChain.targetFormat(0),
                PackChain.promoted(0) ? ColorTargetState.WRITE_COLOR : vanillaTarget.writeMask()));
        if (vanilla.getDepthStencilState() != null) {
            b.withDepthStencilState(vanilla.getDepthStencilState());
        }
        BindGroupLayout.Builder bindings = BindGroupLayout.builder();
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
            throw new IllegalStateException(program + " for " + vanilla.getLocation() + ": the device returned no pipeline");
        }
        Std140Layout.Layout layout = Std140Layout.of(r.uniforms());
        Map<String, String> defaults = new HashMap<>();
        r.uniforms().forEach(u -> {
            if (u.defaultValue() != null) {
                defaults.put(u.name(), u.defaultValue());
            }
        });
        Piece piece = new Piece(vanilla, program, r, layout, defaults, compiled, new ArrayList<>());
        pieces.put(vanilla, piece);
        byCompiled.put(compiled, piece);
    }

    /** Frame start (from PackChain.beginFrame, no pass open): resolve inputs, reset per-frame state. */
    static void beginFrame(GpuDevice device, CommandEncoder encoder) {
        usedRings.forEach(MappableRingBuffer::rotate);
        usedRings.clear();
        drawsThisFrame = 0;
        if (!active()) {
            return;
        }
        for (Piece p : pieces.values()) {
            for (ProgramTranslator.Sampler s : p.result().samplers()) {
                if (!ALBEDO.contains(s.name())) {
                    inputs.computeIfAbsent(s.name(), n -> null);
                    inputs.put(s.name(), PackChain.inputView(s.name()));
                }
            }
        }
    }

    // ---- Called from SkyRendererMixin ---------------------------------------------------------

    public static void enterPass() {
        inSkyPass = active();
    }

    public static void leavePass() {
        inSkyPass = false;
    }

    public static boolean inPass() {
        return inSkyPass;
    }

    public static void noteLabel(String label) {
        pendingLabel = label;
    }

    public static void noteTransform(Matrix4fc modelView, Vector4f color) {
        pendingModelView = new Matrix4f(modelView);
        pendingColor = new Vector4f(color);
    }

    /** Our pipeline in place of vanilla's, or vanilla's own if we don't take this piece. */
    public static CompiledRenderPipeline pipelineFor(RenderPipeline vanilla, CompiledRenderPipeline original) {
        if (!inSkyPass) {
            return original;
        }
        Piece p = pieces.get(vanilla);
        return p != null ? p.compiled() : original;
    }

    /** After setPipeline: bind this piece's uniform block and textures. */
    public static void bindFor(RenderPass pass, CompiledRenderPipeline compiled, Function<String, Object> values) {
        Piece p = byCompiled.get(compiled);
        if (p == null || !inSkyPass) {
            return;
        }
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            GpuTextureView v = inputs.get(s.name());
            if (v != null) {
                pass.setUniform(s.name(), v, linear);
            }
        }
        if (p.layout().size() == 0) {
            return;
        }
        int slot = drawsThisFrame++;
        while (p.rings().size() <= slot) {
            p.rings().add(new MappableRingBuffer(() -> "Luxium sky uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, p.layout().size()));
        }
        MappableRingBuffer ring = p.rings().get(slot);
        Function<String, Object> perDraw = perDrawValues(values);
        try (GpuBufferSlice.MappedView view = ring.currentBuffer().map(false, true)) {
            UniformWriter.write(p.layout(), perDraw, p.defaults(), view.data());
        }
        pass.setUniform(ProgramTranslator.UNIFORM_BLOCK, ring.currentBuffer());
        usedRings.add(ring);
    }

    /** Vanilla binds the sun/moon texture as Sampler0; the pack reads it under its own name. */
    public static void bindAlbedo(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
        if (!inSkyPass || !name.equals("Sampler0")) {
            return;
        }
        for (Piece p : pieces.values()) {
            for (ProgramTranslator.Sampler s : p.result().samplers()) {
                if (ALBEDO.contains(s.name())) {
                    pass.setUniform(s.name(), view, sampler);
                }
            }
        }
    }

    /** The values that differ per sky piece, over the frame's values. */
    private static Function<String, Object> perDrawValues(Function<String, Object> values) {
        Matrix4f modelView = new Matrix4f(pendingModelView);
        Matrix4f projection = (Matrix4f) values.apply("gbufferProjection");
        Matrix4f proj = projection == null ? new Matrix4f() : projection;
        Matrix4f mvp = new Matrix4f(proj).mul(modelView);
        Matrix3f normal = new Matrix3f(modelView).invert().transpose();
        Matrix4fc[] textureMatrices = new Matrix4fc[8];
        java.util.Arrays.fill(textureMatrices, new Matrix4f());
        int stage = stageFor(pendingLabel);
        Vector4f color = pendingColor;
        return name -> switch (name) {
            case "luxium_ModelViewMatrix" -> modelView;
            case "luxium_ProjectionMatrix" -> proj;
            case "luxium_ModelViewProjectionMatrix" -> mvp;
            case "luxium_NormalMatrix" -> normal;
            case "luxium_TextureMatrix" -> textureMatrices;
            case "luxium_ColorModulator" -> new float[]{color.x, color.y, color.z, color.w};
            case "renderStage" -> stage;
            default -> values.apply(name);
        };
    }

    /** Iris's render stages (WorldRenderingPhase order) for vanilla's sky debug labels. */
    static int stageFor(String label) {
        return switch (label) {
            case "Sky disc" -> 1;          // SKY
            case "Sunrise sunset" -> 2;     // SUNSET (SkyRenderer.java:300)
            case "Sun" -> 4;               // SUN
            case "Moon" -> 5;              // MOON
            case "Stars" -> 6;             // STARS
            case "Dark disc" -> 7;         // VOID
            default -> 0;
        };
    }
}
