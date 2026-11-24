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
import net.minecraft.world.level.dimension.DimensionType;
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
 * gets its own model-view (the day rotation is the sun's position) and its renderStage.
 * <p>
 * Step 7b: the End's sky too. Iris draws vanilla's END_SKY pipeline with gbuffers_skytextured
 * (IrisPipelines: END_SKY -> SKY_TEXTURED), and the End flash is a CELESTIAL draw; both are in
 * Iris's CUSTOM_SKY stage, because none of the overworld sky calls that set a stage run there
 * (MixinLevelRenderer, MixinSkyRenderer). A world with no sky (the Nether) has no sky pass.
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
    /** The current world's sky type (DimensionType.Skybox), set every frame. */
    private static DimensionType.Skybox skybox = DimensionType.Skybox.OVERWORLD;

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
        return prepared && skybox != DimensionType.Skybox.NONE && PackChain.ready();
    }

    public static void setSkybox(DimensionType.Skybox current) {
        skybox = current;
    }

    /** Compiles the pack's two sky programs once per vanilla sky pipeline. */
    public static void prepare(String[] skybasic, String[] skytextured) {
        try {
            add(RenderPipelines.SKY, "gbuffers_skybasic", skybasic);
            add(RenderPipelines.STARS, "gbuffers_skybasic", skybasic);
            add(RenderPipelines.SUNRISE_SUNSET, "gbuffers_skybasic", skybasic);
            add(RenderPipelines.CELESTIAL, "gbuffers_skytextured", skytextured);
            add(RenderPipelines.END_SKY, "gbuffers_skytextured", skytextured);
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
        Identifier shader = Identifier.fromNamespaceAndPath("luxium", "pack/" + program + "_" + vanilla.getLocation().getPath().replace('/', '_'));
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("luxium", "pipeline/sky/" + vanilla.getLocation().getPath()))
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

    /** Forgets the pack's sky programs (a new world folder is being loaded; see PackChain.unload). */
    static void unload() {
        for (Piece p : pieces.values()) {
            p.compiled().close();
            p.rings().forEach(MappableRingBuffer::close);
        }
        pieces.clear();
        byCompiled.clear();
        inputs.clear();
        usedRings.clear();
        prepared = false;
        inSkyPass = false;
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
                    inputs.put(s.name(), PackChain.inputView(s.name(), p.result().samplers()));
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
        for (ProgramTranslator.Sampler s : p.result().samplers()) {
            GpuTextureView v = inputs.get(s.name());
            if (v != null) {
                pass.setUniform(s.name(), v, CustomTextures.samplerFor(s.name(), v));
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

    // ---- The horizon (Iris HorizonRenderer) -----------------------------------------------

    /** Iris HorizonRenderer: the cone lies between the sky box's bottom and top planes. */
    private static final float HORIZON_TOP = 16.0f;
    private static final float HORIZON_BOTTOM = -16.0f;
    private static GpuBuffer horizon;
    private static int horizonRadius = -1;
    /** A triangle fan of the centre and nine rim points (the ninth closes the ring). */
    private static final int HORIZON_VERTICES = 10;

    /**
     * Step 7g: the horizon, drawn first in the pack's sky pass. Vanilla only draws a plane above
     * the player and leaves the rest to the clear colour; Iris draws an inverted octagonal cone
     * around the player with the sky pipeline (so the pack's gbuffers_skybasic, stage SKY), the
     * camera's rotation and the fog colour, so the pack shades the sky below the horizon too
     * (IrisRenderingPipeline.onBeginClear; see NOTICE). Without it that area stayed black and
     * showed as a dark band where the world meets the sky.
     */
    public static void drawHorizon(RenderPass pass, Function<String, Object> values) {
        Piece p = pieces.get(RenderPipelines.SKY);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (!inSkyPass || p == null || mc.level == null
                || !(skybox == DimensionType.Skybox.OVERWORLD || mc.level.dimensionType().hasSkyLight())
                || !(values.apply("gbufferModelView") instanceof Matrix4fc modelView)) {
            return;
        }
        int radius = Math.min(mc.options.getEffectiveRenderDistance() * 16, 256);
        if (horizon == null || radius != horizonRadius) {
            if (horizon != null) {
                horizon.close();
            }
            java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(HORIZON_VERTICES * 12).order(java.nio.ByteOrder.nativeOrder());
            data.putFloat(0f).putFloat(HORIZON_BOTTOM).putFloat(0f);
            for (int i = 0; i <= 8; i++) {
                double angle = -i * Math.PI / 4.0;
                data.putFloat((float) (radius * Math.cos(angle))).putFloat(HORIZON_TOP).putFloat((float) (radius * Math.sin(angle)));
            }
            data.flip();
            horizon = RenderSystem.getDevice().createBuffer(() -> "Luxium horizon", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, data);
            horizonRadius = radius;
        }
        float[] fog = io.github.hecbatista.metalshaders.uniform.GameUniforms.fogColor();
        noteLabel("Sky disc");   // stage SKY, as Iris sets for the horizon
        noteTransform(modelView, new Vector4f(fog[0], fog[1], fog[2], 1f));
        pass.setPipeline(p.compiled());
        bindFor(pass, p.compiled(), values);
        // A triangle fan drawn without indices, as vanilla draws its own sky disc (SkyRenderer.renderSkyDisc).
        pass.setVertexBuffer(0, horizon.slice());
        pass.draw(HORIZON_VERTICES, 1, 0, 0);
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
            case "End sky", "End flash" -> 3;   // CUSTOM_SKY (SkyRenderer.java:316, 236)
            default -> 0;
        };
    }
}
