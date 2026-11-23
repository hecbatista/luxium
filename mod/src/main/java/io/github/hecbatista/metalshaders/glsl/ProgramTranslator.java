package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.glsl.Declarations.Declaration;
import io.github.hecbatista.metalshaders.glsl.Declarations.Declarator;
import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Kind;
import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one program (a vertex stage and a fragment stage, already preprocessed) from
 * OpenGL-era GLSL into GLSL that compiles for Vulkan the way Minecraft compiles it
 * (shaderc, Vulkan 1.2, automatic uniform bindings, no automatic in/out locations).
 * <p>
 * The unit is the program, not the file (Vitrail's lesson, docs/translation.md:211):
 * <ul>
 *   <li>Both stages get the <b>same</b> uniform block: every loose uniform either stage
 *       declares, sorted by name. Vulkan doesn't allow loose uniforms, and the two stages must
 *       agree on the block's layout.</li>
 *   <li>Each value passed from the vertex to the fragment stage gets a location number from one
 *       shared table, by name, so both sides agree.</li>
 * </ul>
 * Version 1: enough to <i>compile</i>. Values (step 5) and binding to Minecraft (step 6) come
 * later; names like {@code luxium_ModelViewMatrix} are placeholders the engine will fill.
 */
public final class ProgramTranslator {
    public static final String UNIFORM_BLOCK = "LuxiumUniforms";

    /** Old function name -> new name. */
    private static final Map<String, String> FUNCTION_RENAMES = Map.of(
            "texture2D", "texture",
            "texture3D", "texture",
            "textureCube", "texture",
            "texture2DLod", "textureLod",
            "texture3DLod", "textureLod",
            "shadow2D", "luxium_shadow2D",
            "shadow2DLod", "luxium_shadow2DLod",
            "ftransform", "luxium_ftransform");

    /** Old built-in uniforms: name -> (new name, type, array suffix). */
    private record Builtin(String name, String type, String array) {
    }

    private static final Map<String, Builtin> BUILTIN_UNIFORMS = Map.of(
            "gl_ModelViewMatrix", new Builtin("luxium_ModelViewMatrix", "mat4", ""),
            "gl_ProjectionMatrix", new Builtin("luxium_ProjectionMatrix", "mat4", ""),
            "gl_ModelViewProjectionMatrix", new Builtin("luxium_ModelViewProjectionMatrix", "mat4", ""),
            "gl_NormalMatrix", new Builtin("luxium_NormalMatrix", "mat3", ""),
            "gl_TextureMatrix", new Builtin("luxium_TextureMatrix", "mat4", "[8]"),
            "gl_Fog", new Builtin("luxium_Fog", "LuxiumFog", ""));

    /** Old built-in vertex inputs. */
    private static final Map<String, Builtin> BUILTIN_INPUTS = Map.of(
            "gl_Vertex", new Builtin("luxium_Vertex", "vec4", ""),
            "gl_Color", new Builtin("luxium_Color", "vec4", ""),
            "gl_Normal", new Builtin("luxium_Normal", "vec3", ""),
            "gl_MultiTexCoord0", new Builtin("luxium_MultiTexCoord0", "vec4", ""),
            "gl_MultiTexCoord1", new Builtin("luxium_MultiTexCoord1", "vec4", ""),
            "gl_MultiTexCoord2", new Builtin("luxium_MultiTexCoord2", "vec4", ""));

    /**
     * Core-profile names Iris supplies when a pack uses them without declaring them
     * (e.g. Complementary's gbuffers_line). Iris replaces them in VanillaCoreTransformer and
     * friends; here they only get declared so the program compiles.
     */
    private static final Map<String, Builtin> CORE_UNIFORMS = Map.of(
            "projectionMatrix", new Builtin("projectionMatrix", "mat4", ""),
            "modelViewMatrix", new Builtin("modelViewMatrix", "mat4", ""),
            "projectionMatrixInverse", new Builtin("projectionMatrixInverse", "mat4", ""),
            "modelViewMatrixInverse", new Builtin("modelViewMatrixInverse", "mat4", ""),
            "normalMatrix", new Builtin("normalMatrix", "mat3", ""),
            "textureMatrix", new Builtin("textureMatrix", "mat4", ""));
    private static final Map<String, Builtin> CORE_INPUTS = Map.of(
            "vaPosition", new Builtin("vaPosition", "vec3", ""),
            "vaColor", new Builtin("vaColor", "vec4", ""),
            "vaNormal", new Builtin("vaNormal", "vec3", ""),
            "vaUV0", new Builtin("vaUV0", "vec2", ""),
            "vaUV1", new Builtin("vaUV1", "ivec2", ""),
            "vaUV2", new Builtin("vaUV2", "ivec2", ""));

    /** DRAWBUFFERS lists one digit per target (0-9); RENDERTARGETS is the comma form that can go past 9. */
    private static final Pattern DRAW_BUFFERS = Pattern.compile("^/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9]+)");
    private static final Pattern RENDER_TARGETS = Pattern.compile("^/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9, ]+)");

    public enum Stage { VERTEX, FRAGMENT }

    /**
     * GBUFFERS: geometry programs, fed by real vertex data.
     * FULLSCREEN: composite/deferred/final programs, drawn as one triangle over the screen with no
     * vertex buffer. The old built-ins become fixed values, as Iris's CompositeTransformer does:
     * position and texture coordinate come from the triangle, color is white, the normal is
     * (0,0,1), matrices are identity except a projection mapping 0..1 onto the screen.
     */
    public enum Mode { GBUFFERS, FULLSCREEN }

    /** Corner of the full-screen triangle, 0..1 in screen terms: (0,0), (2,0), (0,2). */
    private static final String FS_CORNER = "vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2)";
    /**
     * The projection mapping the 0..1 quad onto the screen. A pack's {@code scale.<program> = s}
     * draws the program into the 0..s part of its targets; Iris does that with a smaller
     * viewport, and since Metallum always sets a full viewport, the scale goes into this matrix.
     */
    static String fsProjection(float scale) {
        String s = Float.toString(2.0f * scale);
        return "mat4(vec4(" + s + ", 0.0, 0.0, 0.0), vec4(0.0, " + s + ", 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0))";
    }

    private static final String FS_PROJECTION = fsProjection(1.0f);

    /** Full-screen replacements in both stages, for a program drawn at {@code scale}. */
    private static Map<String, String> fullscreenBoth(float scale) {
        String projection = fsProjection(scale);
        return Map.of(
                "gl_Color", "vec4(1.0)",
                "gl_NormalMatrix", "mat3(1.0)",
                "gl_ModelViewMatrix", "mat4(1.0)",
                "gl_ProjectionMatrix", projection,
                "gl_ModelViewProjectionMatrix", "(" + projection + " * mat4(1.0))");
    }

    private static final Map<String, String> FULLSCREEN_BOTH = fullscreenBoth(1.0f);

    /** Full-screen replacements in the vertex stage only. */
    private static final Map<String, String> FULLSCREEN_VERTEX = Map.of(
            "gl_Vertex", "vec4(" + FS_CORNER + ", 0.0, 1.0)",
            "gl_MultiTexCoord0", "vec4(" + FS_CORNER + ", 0.0, 1.0)",
            "gl_Normal", "vec3(0.0, 0.0, 1.0)");

    /** Which mode a program runs in, by its name (e.g. "composite3", "final", "gbuffers_terrain"). */
    public static Mode modeFor(String program) {
        return program.matches("(composite|deferred|prepare|begin|shadowcomp)\\d*(_.*)?|final") ? Mode.FULLSCREEN : Mode.GBUFFERS;
    }

    /** A sampler or image the program declares; the pipeline has to list and bind every one. */
    public record Sampler(String type, String name) {
    }

    /** A loose uniform that became a block member. {@code defaultValue} is its initializer, if any. */
    public record Uniform(String type, String name, String array, String defaultValue) {
    }

    /** An input or output with the location it was given. */
    public record Slot(String name, String type, String array, int location) {
    }

    /**
     * The translated program and what the engine needs to know to run it.
     *
     * @param drawBuffers  which colortex each fragment output N writes to, from the
     *                     DRAWBUFFERS/RENDERTARGETS comment (empty if there was none)
     * @param warnings     things that look wrong but don't stop compiling
     */
    public record Result(String vertex, String fragment, List<Uniform> uniforms, List<Slot> vertexInputs,
                         List<Slot> varyings, List<Slot> fragmentOutputs, List<Integer> drawBuffers,
                         List<Sampler> samplers, List<String> warnings) {
    }

    /** Thrown when the program uses something version 1 can't translate. */
    public static final class TranslationException extends Exception {
        public TranslationException(String message) {
            super(message);
        }
    }

    private ProgramTranslator() {
    }

    // ---------------------------------------------------------------------------------------

    /** What one stage declares and uses, before anything is rewritten. */
    private static final class StageScan {
        final Stage stage;
        final List<Token> tokens;
        final List<String> extensions = new ArrayList<>();
        final List<Declaration> declarations;
        final Set<String> identifiers = new LinkedHashSet<>();
        final Set<String> declaredNames = new LinkedHashSet<>();
        final Map<String, Integer> identifierCount = new java.util.HashMap<>();

        StageScan(Stage stage, String source) {
            this.stage = stage;
            this.tokens = new ArrayList<>(GlslTokenizer.tokenize(source));
            for (Token t : tokens) {
                if (t.kind() == Kind.IDENT) {
                    identifiers.add(t.text());
                    identifierCount.merge(t.text(), 1, Integer::sum);
                } else if (t.kind() == Kind.DIRECTIVE && t.text().startsWith("#extension")) {
                    extensions.add(t.text());
                }
            }
            this.declarations = Declarations.find(tokens);
            for (Declaration d : declarations) {
                for (Declarator x : d.declarators()) {
                    declaredNames.add(x.name());
                }
            }
        }

        /**
         * Whether a sampler declared here is mentioned anywhere but its own declaration. Unmentioned
         * ones are dropped: the game's reflection lists every declared resource and gives each a
         * Metal slot, and Metal has only 16 sampler slots per stage (0-15), so a pack that declares
         * every texture in a shared file runs out (Vitrail hit the same, docs/translation.md:182-187).
         */
        boolean mentions(String sampler) {
            long declaredHere = declarations.stream().flatMap(d -> d.declarators().stream())
                    .filter(x -> x.name().equals(sampler)).count();
            return identifierCount.getOrDefault(sampler, 0) > declaredHere;
        }
    }

    /** Metal's sampler slots per stage ([[sampler(0..15)]]). */
    static final int MAX_SAMPLERS_PER_STAGE = 16;

    public static Result translate(String vertexSource, String fragmentSource) throws TranslationException {
        return translate(vertexSource, fragmentSource, Mode.GBUFFERS);
    }

    public static Result translate(String vertexSource, String fragmentSource, Mode mode) throws TranslationException {
        return translate(vertexSource, fragmentSource, mode, 1.0f);
    }

    /** @param scale the pack's scale.<program> value (1 = whole screen); only used in FULLSCREEN mode */
    public static Result translate(String vertexSource, String fragmentSource, Mode mode, float scale) throws TranslationException {
        return translate(vertexSource, fragmentSource, mode, scale, null, null, null);
    }

    /** Vanilla vertex-format element names and the GLSL type each arrives as. */
    static final Map<String, String> ELEMENT_TYPES = Map.of(
            "Position", "vec3", "Color", "vec4", "UV0", "vec2", "UV1", "ivec2", "UV2", "ivec2", "UV3", "vec2", "Normal", "vec3");

    /**
     * Translates a geometry program to be drawn with one of the game's own vertex formats.
     * {@code elements} are that format's element names (e.g. Position, UV0): the game binds inputs
     * by name and refuses a shader input the format lacks (PipelineBuilder.java:105-135), so exactly
     * the used elements are declared, and old built-ins the format doesn't carry become constants.
     * gl_Color is the vertex color times the draw's color modulator, as Iris's VanillaTransformer does.
     */
    public static Result translateForFormat(String vertexSource, String fragmentSource, Set<String> elements) throws TranslationException {
        return translate(vertexSource, fragmentSource, Mode.GBUFFERS, 1.0f, Set.copyOf(elements), null, null);
    }

    /**
     * Options for a geometry program drawn by one of the game's own draws (entities, block
     * entities, items) with the game's per-draw DynamicTransforms block bound.
     *
     * @param alphaTest      as {@link Terrain#alphaTest()}
     * @param coverage       as {@link Terrain#coverage()}
     * @param samplerRenames pack sampler name -> the game's (e.g. tex -> Sampler0), so the game's
     *                       own draw binds the texture it would have bound for its shader
     * @param handDepth      squeeze the depth into 0.4375..0.5625 (OpenGL convention) as Iris does
     *                       for the hand (HandRenderer.DEPTH = 0.125, MC_HAND_DEPTH), so packs can
     *                       tell hand pixels apart (Complementary: depth < 0.56)
     * @param glint          the game's pipeline draws the enchantment glint inline (26.3 shader
     *                       define GLINT, with a GlintSampler): its DynamicTransforms.TextureMat is
     *                       the glint's scrolling matrix, not the albedo's, so gl_TextureMatrix[0]
     *                       is the identity, and the pack's mc_hasGlint()/mc_sampleGlint() sample
     *                       the glint as Iris's VanillaTransformer and GlintTransformer do (see NOTICE)
     */
    public record Format(float alphaTest, boolean coverage, Map<String, String> samplerRenames, boolean handDepth, boolean overlay,
                         boolean shadow, boolean glint) {
        public Format(float alphaTest, boolean coverage, Map<String, String> samplerRenames) {
            this(alphaTest, coverage, samplerRenames, false, true, false, false);
        }

        public Format(float alphaTest, boolean coverage, Map<String, String> samplerRenames, boolean handDepth) {
            this(alphaTest, coverage, samplerRenames, handDepth, true, false, false);
        }

        public Format(float alphaTest, boolean coverage, Map<String, String> samplerRenames, boolean handDepth, boolean overlay) {
            this(alphaTest, coverage, samplerRenames, handDepth, overlay, false, false);
        }

        public Format(float alphaTest, boolean coverage, Map<String, String> samplerRenames, boolean handDepth, boolean overlay,
                      boolean shadow) {
            this(alphaTest, coverage, samplerRenames, handDepth, overlay, shadow, false);
        }
    }

    /** The game's glint texture in its GLINT pipelines (BindGroupLayouts.GLINT_SAMPLER, 26.3). */
    public static final String GLINT_SAMPLER = "GlintSampler";
    /** The glint strength option (Iris's glintAlpha uniform), in the program's block. */
    public static final String GLINT_ALPHA = "luxium_GlintAlpha";
    /** The varying carrying the glint's texture coordinate (Iris's iris_glintCoord). */
    static final String GLINT_COORD = "luxium_GlintCoord";

    /**
     * In a shadow-pass game draw, gl_ModelViewMatrix = luxium_ShadowFix * ModelViewMat, where the
     * engine sets luxium_ShadowFix = shadowModelView * inverse(camera view): the draw's own pose,
     * seen from the light instead of the camera.
     */
    public static final String SHADOW_FIX = "luxium_ShadowFix";

    /** The game's overlay texture (hit flash, damage tint), bound by its entity draws (entity.vsh). */
    public static final String OVERLAY_SAMPLER = "Sampler1";

    /** The game's per-draw transform block (minecraft:include/dynamictransforms.glsl, 26.3). */
    public static final String DYNAMIC_TRANSFORMS = "DynamicTransforms";
    private static final String DYNAMIC_TRANSFORMS_BLOCK = """
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat;
                mat4 TextureMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
            };
            """;

    /**
     * Translates a program for one of the game's own draws: inputs from the game's vertex format
     * by element name (as {@link #translateForFormat}), and the per-draw values from the game's
     * DynamicTransforms block, as Iris's VanillaTransformer reads them (see NOTICE):
     * gl_ModelViewMatrix = ModelViewMat, gl_TextureMatrix[0] = TextureMat, gl_Color = Color *
     * ColorModulator, gl_MultiTexCoord1/2 = the raw light pair UV2 (scaled by gl_TextureMatrix[1]).
     */
    public static Result translateForDraw(String vertexSource, String fragmentSource, Set<String> elements, Format format)
            throws TranslationException {
        return translate(vertexSource, fragmentSource, Mode.GBUFFERS, 1.0f, Set.copyOf(elements), null, format);
    }

    // ---- Terrain: Sodium's chunk vertex ------------------------------------------------------

    /** The uniform block Sodium binds for its chunk passes (sodium:include/globals.glsl, 0.9.2). */
    public static final String SODIUM_GLOBALS = "u_Globals";
    /** Bytes Sodium pushes per region (DefaultChunkRenderer.PUSH_CONSTANT_RANGE). */
    public static final int SODIUM_PUSH_CONSTANT_SIZE = 20;
    /** Name of the fragment output carrying the coverage mask in terrain mode. */
    public static final String COVERAGE_OUTPUT = "luxium_Coverage";

    /**
     * How a terrain program is drawn.
     *
     * @param alphaTest the pass's alpha test on output 0 ({@code > alphaTest} keeps the fragment),
     *                  or NaN for none (Iris: solid terrain OFF, cutout HALF_ALPHA = 0.5)
     * @param coverage  whether the fragment stage writes the coverage mask as one more output
     *                  (opaque terrain, drawn before the seed; translucents are drawn after it)
     */
    public record Terrain(float alphaTest, boolean coverage, boolean shadow) {
        public Terrain(float alphaTest, boolean coverage) {
            this(alphaTest, coverage, false);
        }
    }

    /**
     * The vertex elements of Luxium's chunk vertex: Sodium 0.9.2's compact vertex (CompactChunkVertex,
     * 20 bytes) followed by the values packs read that it lacks. Names match
     * {@code io.github.hecbatista.metalshaders.sodium.LuxiumChunkVertex}.
     */
    static final Map<String, String> TERRAIN_ELEMENT_TYPES = Map.of(
            "a_Position", "uvec2", "a_Color", "vec4", "a_TexCoord", "uvec2", "a_LightAndData", "uvec4",
            "luxium_BlockId", "uint", "luxium_MidTexCoord", "uvec2", "luxium_NormalIn", "vec4", "luxium_TangentIn", "vec4");

    /**
     * Translates a geometry program to draw Sodium's chunk meshes, with Luxium's extended vertex.
     * The decoding follows Sodium's chunk_vertex.glsl and Iris's SodiumTransformer (see NOTICE):
     * position from 20-bit interleaved coordinates plus the region offset (a push constant) and the
     * section's place in the region; texture coordinate with Sodium's shrink bias; the light pair raw
     * (0..240), scaled by gl_TextureMatrix[1] as in Iris. The fragment stage writes the coverage
     * mask (its own depth) as one extra output after the pack's draw buffers.
     */
    public static Result translateForTerrain(String vertexSource, String fragmentSource, Terrain terrain) throws TranslationException {
        return translate(vertexSource, fragmentSource, Mode.GBUFFERS, 1.0f, null, terrain, null);
    }

    /** Terrain-mode header: Sodium's blocks and the decoding helpers (from sodium chunk_vertex.glsl). */
    private static final String TERRAIN_VERTEX_HEADER = """
            layout(std140) uniform u_Globals {
                mat4 u_ProjectionMatrix;
                mat4 u_ModelViewMatrix;
                vec4 u_FogColor;
                vec2 u_EnvironmentFog;
                vec2 u_RenderFog;
                vec2 u_TexelSize;
                vec2 u_TexCoordShrink;
                float u_FadePeriodInv;
                bool u_UseRGSS;
            };
            layout(push_constant) uniform LuxiumSodiumRegion {
                vec3 u_RegionOffset;
                int u_CurrentTime;
                uint u_RegionID;
            };
            uvec3 luxium_deinterleave_u20x3(uvec2 data) {
                uvec3 hi = (uvec3(data.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
                uvec3 lo = (uvec3(data.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
                return (hi << 10u) | lo;
            }
            vec3 luxium_draw_translation(uint pos) {
                return vec3(uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u)) * vec3(16.0);
            }
            vec4 luxium_terrain_vertex() {
                vec3 p = vec3(luxium_deinterleave_u20x3(a_Position)) * (32.0 / 1048576.0) - 8.0;
                return vec4(p + u_RegionOffset + luxium_draw_translation(a_LightAndData.w), 1.0);
            }
            """;
    private static final String TERRAIN_TEXCOORD = "vec2 luxium_terrain_texcoord() {\n"
            + "    vec2 uv = vec2(a_TexCoord & 32767u) / 32768.0;\n"
            + "    vec2 bias = mix(vec2(-1.0), vec2(1.0), bvec2(a_TexCoord >> 15u));\n"
            + "    return bias * u_TexCoordShrink + uv;\n"
            + "}\n";
    /** Sodium adds 8 (half a lightmap texel) to each light level; Iris's own mesh stores it raw. */
    private static final String TERRAIN_LIGHT = "vec4(vec2(a_LightAndData.xy) - 8.0, 0.0, 1.0)";

    /** Built-in vertex inputs of terrain mode -> expression. */
    private static String terrainExpression(String name) {
        return switch (name) {
            case "gl_Vertex" -> "luxium_terrain_vertex()";
            case "gl_Color" -> "a_Color";
            case "gl_MultiTexCoord0" -> "vec4(luxium_terrain_texcoord(), 0.0, 1.0)";
            case "gl_MultiTexCoord1", "gl_MultiTexCoord2" -> TERRAIN_LIGHT;
            case "gl_Normal" -> "luxium_NormalIn.xyz";
            default -> name.matches("gl_MultiTexCoord[3-7]") ? "vec4(0.0, 0.0, 0.0, 1.0)" : null;
        };
    }

    /**
     * Pack attributes the terrain mesh carries, as a vec4 expression. mc_Entity is Iris's encoding
     * (id + 1, shifted up one bit, low bit = fluid), so an unmapped block reads -1;
     * mc_midTexCoord is in 1/32768 units (SodiumTransformer.replaceMidTexCoord).
     */
    private static String terrainAttribute(String name) {
        return switch (name) {
            case "mc_Entity" -> "vec4(float(int(luxium_BlockId >> 1u) - 1), float(luxium_BlockId & 1u), 0.0, 1.0)";
            case "mc_midTexCoord" -> "vec4(vec2(luxium_MidTexCoord) / 32768.0, 0.0, 1.0)";
            case "at_tangent" -> "luxium_TangentIn";
            default -> null;
        };
    }

    /** The mesh element a pack attribute (or built-in) reads in terrain mode. */
    private static String terrainElementFor(String name) {
        return switch (name) {
            case "mc_Entity" -> "luxium_BlockId";
            case "mc_midTexCoord" -> "luxium_MidTexCoord";
            case "at_tangent" -> "luxium_TangentIn";
            case "gl_Normal" -> "luxium_NormalIn";
            case "gl_Color" -> "a_Color";
            case "gl_MultiTexCoord0" -> "a_TexCoord";
            default -> null;
        };
    }

    /** A vec4 expression converted to a declared GLSL type (float, vec3, ivec2...). */
    static String fromVec4(String type, String vec4) {
        Matcher m = Pattern.compile("^(|i|u|b)(vec|)([234]?)$|^(float|int|uint|bool)$").matcher(type);
        if (!m.matches()) {
            return type + "(0)";
        }
        if (m.group(4) != null) {
            return m.group(4) + "((" + vec4 + ").x)";
        }
        String n = m.group(3);
        String swizzle = switch (n) {
            case "2" -> "xy";
            case "3" -> "xyz";
            default -> "xyzw";
        };
        return type + "((" + vec4 + ")." + swizzle + ")";
    }

    private static Result translate(String vertexSource, String fragmentSource, Mode mode, float scale, Set<String> elements, Terrain terrain,
                                    Format format) throws TranslationException {
        boolean dynamic = format != null;
        Map<String, String> renames = format == null ? Map.of() : format.samplerRenames();
        StageScan vs = new StageScan(Stage.VERTEX, vertexSource);
        StageScan fs = new StageScan(Stage.FRAGMENT, fragmentSource);
        // entityColor (the hit flash) from the overlay texture, as Iris's EntityPatcher does: a
        // value the vertex stage reads from Sampler1 at UV1 and hands to the fragment stage.
        // overlay = false: the draw binds no overlay texture (e.g. spider eyes); entityColor then
        // stays a uniform, 0, as Iris's CommonUniforms supplies it outside the overlay path.
        boolean overlay = dynamic && format.overlay() && elements.contains("UV1")
                && (vs.declaredNames.contains("entityColor") || fs.declaredNames.contains("entityColor"));
        // The inline glint (Iris GlintTransformer): only where the pack samples it in the fragment
        // stage of a GLINT pipeline's draw. Its coordinate is TextureMat * UV0 (UV3 in the
        // ENTITY_GLINT_SPECIAL format), as the game's item.vsh computes texCoordGlint.
        boolean glintSampling = dynamic && format.glint() && fs.identifiers.contains("mc_sampleGlint");
        String glintUv = elements != null && elements.contains("UV3") ? "UV3" : "UV0";
        List<String> warnings = new ArrayList<>();
        boolean fullscreen = mode == Mode.FULLSCREEN;

        // 0. Every sampler/image either stage declares and mentions (pipeline layout and binding need
        //    all of them; unmentioned ones are dropped from the source in rewrite()).
        Map<String, Sampler> samplers = new TreeMap<>();
        for (StageScan s : List.of(vs, fs)) {
            int inStage = 0;
            for (Declaration d : s.declarations) {
                if (d.storage().equals("uniform") && isOpaque(d.type())) {
                    for (Declarator x : d.declarators()) {
                        if (!s.mentions(x.name())) {
                            continue;
                        }
                        inStage++;
                        String sname = renames.getOrDefault(x.name(), x.name());
                        Sampler old = samplers.putIfAbsent(sname, new Sampler(d.type(), sname));
                        if (old != null && !old.type().equals(d.type())) {
                            throw new TranslationException("Sampler " + x.name() + " is " + old.type()
                                    + " in one stage and " + d.type() + " in the other");
                        }
                    }
                }
            }
            if (inStage > MAX_SAMPLERS_PER_STAGE) {
                throw new TranslationException("The " + s.stage.name().toLowerCase() + " stage uses " + inStage
                        + " textures; Metal allows " + MAX_SAMPLERS_PER_STAGE + " per stage");
            }
        }

        if (overlay) {
            samplers.putIfAbsent(OVERLAY_SAMPLER, new Sampler("sampler2D", OVERLAY_SAMPLER));
        }
        if (glintSampling) {
            long inFragment = fs.declarations.stream().filter(d -> d.storage().equals("uniform") && isOpaque(d.type()))
                    .flatMap(d -> d.declarators().stream()).filter(x -> fs.mentions(x.name())).count();
            if (inFragment + 1 > MAX_SAMPLERS_PER_STAGE) {
                throw new TranslationException("The fragment stage uses " + inFragment + " textures plus the glint; Metal allows "
                        + MAX_SAMPLERS_PER_STAGE + " per stage");
            }
            samplers.putIfAbsent(GLINT_SAMPLER, new Sampler("sampler2D", GLINT_SAMPLER));
        }

        // 1. The shared uniform block: loose uniforms from both stages, plus built-ins used.
        Map<String, Uniform> uniforms = new TreeMap<>();
        for (StageScan s : List.of(vs, fs)) {
            for (Declaration d : s.declarations) {
                if (d.storage().equals("uniform") && !isOpaque(d.type())) {
                    for (Declarator x : d.declarators()) {
                        if (overlay && x.name().equals("entityColor")) {
                            continue;   // becomes a varying (see the overlay prologue)
                        }
                        Uniform u = new Uniform(d.type(), x.name(), x.arraySuffix(), x.initializer());
                        Uniform old = uniforms.putIfAbsent(x.name(), u);
                        if (old != null && !(old.type() + old.array()).equals(u.type() + u.array())) {
                            throw new TranslationException("Uniform " + x.name() + " is " + old.type() + old.array()
                                    + " in one stage and " + u.type() + u.array() + " in the other");
                        }
                    }
                }
            }
            for (Map.Entry<String, Builtin> b : BUILTIN_UNIFORMS.entrySet()) {
                boolean replacedByFullscreen = fullscreen && (FULLSCREEN_BOTH.containsKey(b.getKey()) || b.getKey().equals("gl_TextureMatrix"));
                boolean replacedByDraw = dynamic && Set.of("gl_ModelViewMatrix", "gl_NormalMatrix", "gl_ModelViewProjectionMatrix").contains(b.getKey());
                if (s.identifiers.contains(b.getKey()) && !replacedByFullscreen && !replacedByDraw) {
                    Builtin v = b.getValue();
                    uniforms.putIfAbsent(v.name(), new Uniform(v.type(), v.name(), v.array(), null));
                }
            }
            for (Map.Entry<String, Builtin> b : CORE_UNIFORMS.entrySet()) {
                if (s.identifiers.contains(b.getKey()) && !s.declaredNames.contains(b.getKey())) {
                    Builtin v = b.getValue();
                    uniforms.putIfAbsent(v.name(), new Uniform(v.type(), v.name(), v.array(), null));
                }
            }
            if (elements != null && !dynamic && s.identifiers.contains("gl_Color")) {
                uniforms.putIfAbsent("luxium_ColorModulator", new Uniform("vec4", "luxium_ColorModulator", "", null));
            }
            if (dynamic && format.shadow() && (s.identifiers.contains("gl_ModelViewMatrix") || s.identifiers.contains("gl_NormalMatrix")
                    || s.identifiers.contains("gl_ModelViewProjectionMatrix") || s.identifiers.contains("ftransform"))) {
                uniforms.putIfAbsent(SHADOW_FIX, new Uniform("mat4", SHADOW_FIX, "", null));
            }
            if (dynamic && (s.identifiers.contains("gl_ModelViewProjectionMatrix") || s.identifiers.contains("ftransform"))) {
                uniforms.putIfAbsent("luxium_ProjectionMatrix", new Uniform("mat4", "luxium_ProjectionMatrix", "", null));
            }
            if (glintSampling) {
                uniforms.putIfAbsent(GLINT_ALPHA, new Uniform("float", GLINT_ALPHA, "", null));
            }
            if (s.identifiers.contains("ftransform") && !fullscreen && !dynamic) {
                uniforms.putIfAbsent("luxium_ModelViewProjectionMatrix",
                        new Uniform("mat4", "luxium_ModelViewProjectionMatrix", "", null));
            }
        }

        // 2. Vertex inputs: built-ins used, then the pack's own attribute/in, each by name.
        Map<String, Slot> vertexInputs = new LinkedHashMap<>();
        int nextInput = 0;
        Map<String, Builtin> usedInputs = new TreeMap<>();
        if (elements != null) {
            // Inputs come from the game's format, by element name.
            Map<String, Boolean> needs = new java.util.LinkedHashMap<>();
            needs.put("Position", vs.identifiers.contains("gl_Vertex") || vs.identifiers.contains("ftransform"));
            needs.put("Color", vs.identifiers.contains("gl_Color"));
            needs.put("UV0", vs.identifiers.contains("gl_MultiTexCoord0"));
            needs.put("UV2", vs.identifiers.contains("gl_MultiTexCoord1") || vs.identifiers.contains("gl_MultiTexCoord2"));
            needs.put("Normal", vs.identifiers.contains("gl_Normal"));
            needs.put("UV1", overlay);
            needs.put("UV3", glintSampling && glintUv.equals("UV3"));
            if (glintSampling && glintUv.equals("UV0")) {
                needs.put("UV0", true);
            }
            for (Map.Entry<String, Boolean> e : needs.entrySet()) {
                if (e.getValue() && elements.contains(e.getKey())) {
                    String type = ELEMENT_TYPES.get(e.getKey());
                    vertexInputs.put(e.getKey(), new Slot(e.getKey(), type, "", nextInput));
                    nextInput += 1;
                }
            }
        }
        if (terrain != null) {
            // Inputs are elements of the chunk mesh, by name (PipelineBuilder binds each declared
            // input to the element of the same name). Position and the light/data word are always
            // read: the section index in the latter places the vertex.
            Set<String> used = new LinkedHashSet<>(List.of("a_Position", "a_LightAndData"));
            for (String name : List.of("gl_Color", "gl_MultiTexCoord0", "gl_Normal")) {
                if (vs.identifiers.contains(name)) {
                    used.add(terrainElementFor(name));
                }
            }
            for (Declaration d : vs.declarations) {
                if (d.storage().equals("attribute") || d.storage().equals("in")) {
                    for (Declarator x : d.declarators()) {
                        String element = terrainElementFor(x.name());
                        if (element != null && vs.mentions(x.name())) {
                            used.add(element);
                        }
                    }
                }
            }
            for (String name : List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData",
                    "luxium_BlockId", "luxium_MidTexCoord", "luxium_NormalIn", "luxium_TangentIn")) {
                if (used.contains(name)) {
                    vertexInputs.put(name, new Slot(name, TERRAIN_ELEMENT_TYPES.get(name), "", nextInput++));
                }
            }
        }
        for (Map.Entry<String, Builtin> b : BUILTIN_INPUTS.entrySet()) {
            if (fullscreen || elements != null || terrain != null) {
                break;   // full-screen programs get no vertex data; format programs get the format's
            }
            if (vs.identifiers.contains(b.getKey()) || (b.getKey().equals("gl_Vertex") && vs.identifiers.contains("ftransform"))) {
                usedInputs.put(b.getValue().name(), b.getValue());
            }
        }
        for (Map.Entry<String, Builtin> b : CORE_INPUTS.entrySet()) {
            if (elements == null && terrain == null && vs.identifiers.contains(b.getKey()) && !vs.declaredNames.contains(b.getKey())) {
                usedInputs.put(b.getValue().name(), b.getValue());
            }
        }
        for (Builtin b : usedInputs.values()) {
            vertexInputs.put(b.name(), new Slot(b.name(), b.type(), b.array(), nextInput));
            nextInput += slots(b.type(), b.array(), warnings);
        }
        for (Declaration d : vs.declarations) {
            if (terrain == null && (d.storage().equals("attribute") || d.storage().equals("in"))) {
                for (Declarator x : d.declarators()) {
                    if (elements != null && !elements.contains(x.name())) {
                        continue;   // the format doesn't carry it: becomes a zero constant in rewrite()
                    }
                    vertexInputs.put(x.name(), new Slot(x.name(), d.type(), x.arraySuffix(), nextInput));
                    nextInput += slots(d.type(), x.arraySuffix(), warnings);
                }
            }
        }

        // 3. Varyings: one table for both stages, sorted by name.
        Map<String, Slot> varyingTypes = new TreeMap<>();
        Set<String> vertexOut = new LinkedHashSet<>();
        Set<String> fragmentIn = new LinkedHashSet<>();
        if (overlay) {
            varyingTypes.put("entityColor", new Slot("entityColor", "vec4", "", -1));
            vertexOut.add("entityColor");
        }
        if (glintSampling) {
            varyingTypes.put(GLINT_COORD, new Slot(GLINT_COORD, "vec2", "", -1));
            vertexOut.add(GLINT_COORD);
        }
        for (Declaration d : vs.declarations) {
            if (d.storage().equals("out") || d.storage().equals("varying")) {
                for (Declarator x : d.declarators()) {
                    varyingTypes.putIfAbsent(x.name(), new Slot(x.name(), d.type(), x.arraySuffix(), -1));
                    vertexOut.add(x.name());
                }
            }
        }
        for (Declaration d : fs.declarations) {
            if (d.storage().equals("in") || d.storage().equals("varying")) {
                for (Declarator x : d.declarators()) {
                    Slot old = varyingTypes.putIfAbsent(x.name(), new Slot(x.name(), d.type(), x.arraySuffix(), -1));
                    if (old != null && !(old.type() + old.array()).equals(d.type() + x.arraySuffix())) {
                        throw new TranslationException("Varying " + x.name() + " is " + old.type() + old.array()
                                + " in the vertex stage and " + d.type() + x.arraySuffix() + " in the fragment stage");
                    }
                    fragmentIn.add(x.name());
                }
            }
        }
        Map<String, Slot> varyings = new LinkedHashMap<>();
        int nextVarying = 0;
        for (Slot v : varyingTypes.values()) {
            varyings.put(v.name(), new Slot(v.name(), v.type(), v.array(), nextVarying));
            nextVarying += slots(v.type(), v.array(), warnings);
        }
        for (String name : fragmentIn) {
            if (!vertexOut.contains(name)) {
                warnings.add("Fragment stage reads '" + name + "' but the vertex stage never writes it");
            }
        }

        // 4. Rewrite each stage.
        List<Slot> fragmentOutputs = new ArrayList<>();
        String vertex = rewrite(vs, mode, scale, elements, terrain, format, uniforms, vertexInputs, varyings, fragmentOutputs, warnings);
        String fragment = rewrite(fs, mode, scale, elements, terrain, format, uniforms, vertexInputs, varyings, fragmentOutputs, warnings);

        return new Result(vertex, fragment, List.copyOf(uniforms.values()), List.copyOf(vertexInputs.values()),
                List.copyOf(varyings.values()), List.copyOf(fragmentOutputs), drawBuffers(fs.tokens),
                List.copyOf(samplers.values()), List.copyOf(warnings));
    }

    private static String rewrite(StageScan s, Mode mode, float scale, Set<String> elements, Terrain terrain, Format format,
                                  Map<String, Uniform> uniforms, Map<String, Slot> vertexInputs,
                                  Map<String, Slot> varyings, List<Slot> fragmentOutputs, List<String> warnings)
            throws TranslationException {
        List<Token> t = s.tokens;
        boolean vertex = s.stage == Stage.VERTEX;
        boolean fullscreen = mode == Mode.FULLSCREEN;
        boolean dynamic = format != null;
        Map<String, String> renames = dynamic ? format.samplerRenames() : Map.of();
        // The fragment epilogue (alpha test, coverage mask): terrain's, or a game draw's.
        Terrain out = terrain != null ? terrain : dynamic ? new Terrain(format.alphaTest(), format.coverage()) : null;
        boolean overlay = dynamic && vertexInputs.containsKey("UV1") && varyings.containsKey("entityColor");

        // a) Replace declarations, last first so earlier indices stay valid.
        List<Declaration> ds = new ArrayList<>(s.declarations);
        for (int k = ds.size() - 1; k >= 0; k--) {
            Declaration d = ds.get(k);
            String replacement = switch (d.storage()) {
                // Loose uniforms move into the block; samplers stay, minus the ones this stage never mentions.
                case "uniform" -> isOpaque(d.type()) ? mentionedSamplers(d, s, renames) : "";
                case "attribute", "in" -> vertex ? (terrain != null ? terrainAttributes(d) : elements != null ? formatInputs(d, vertexInputs) : slotDeclarations(d, "in", vertexInputs))
                        : slotDeclarations(d, "in", varyings);
                case "out", "varying" -> vertex ? slotDeclarations(d, "out", varyings)
                        : d.storage().equals("varying") ? slotDeclarations(d, "in", varyings) : null;
                default -> null;
            };
            if (replacement != null) {
                List<Token> sub = t.subList(d.start(), d.end() + 1);
                int keepLeading = 0;
                while (keepLeading < sub.size() && sub.get(keepLeading).trivia()) {
                    keepLeading++;
                }
                List<Token> leading = new ArrayList<>(sub.subList(0, keepLeading));
                sub.clear();
                sub.addAll(leading);
                sub.add(new Token(Kind.SPACE, replacement));
            }
        }

        // b) Rename identifiers and old built-ins everywhere.
        // Fragment stages of passes drawn onto the game's reversed-Z depth (the vertex epilogue
        // below writes z' = (w - z) / 2) see gl_FragCoord.z reversed too, far = 0. Packs feed it
        // to gbufferProjectionInverse, which is OpenGL's (undoRevZ), so their reads get it back
        // as 1 - z, as Iris's DepthTransformer does (see NOTICE). The epilogue's coverage mask is
        // appended after this rewrite and keeps the raw value the seed compares with the depth.
        boolean reversedFragDepth = !vertex && ((terrain != null && !terrain.shadow())
                || (elements != null && !(dynamic && format.shadow())));
        Set<Integer> outputsUsed = new java.util.TreeSet<>();
        for (int i = 0; i < t.size(); i++) {
            Token tok = t.get(i);
            if (tok.kind() == Kind.DIRECTIVE && (tok.text().startsWith("#version") || tok.text().startsWith("#extension"))) {
                t.set(i, new Token(Kind.SPACE, ""));
                continue;
            }
            if (tok.kind() != Kind.IDENT) {
                continue;
            }
            String name = tok.text();
            if (reversedFragDepth && name.equals("gl_FragCoord")) {
                t.set(i, new Token(Kind.IDENT, "vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w)"));
                continue;
            }
            if (name.equals("sampler2DShadow")) {
                t.set(i, new Token(Kind.IDENT, "sampler2D"));
                continue;
            }
            if ((name.equals("texture") || name.equals("textureLod")) && firstArgumentIsShadow(t, i, s)) {
                t.set(i, new Token(Kind.IDENT, "luxium_shadowCompare"));
                continue;
            }
            if (fullscreen) {
                String expr = fullscreenBoth(scale).get(name);
                if (expr == null && vertex) {
                    expr = FULLSCREEN_VERTEX.get(name);
                    if (expr == null && name.matches("gl_MultiTexCoord[1-7]")) {
                        expr = "vec4(0.0, 0.0, 0.0, 1.0)";
                    }
                }
                if (name.equals("gl_TextureMatrix")) {
                    // gl_TextureMatrix [ N ]  ->  identity
                    int open = nextCode(t, i + 1);
                    int num = open < 0 ? -1 : nextCode(t, open + 1);
                    int close = num < 0 ? -1 : nextCode(t, num + 1);
                    if (open < 0 || !t.get(open).is("[") || num < 0 || close < 0 || !t.get(close).is("]")) {
                        throw new TranslationException("gl_TextureMatrix with an index that isn't a plain number");
                    }
                    for (int q = i + 1; q <= close; q++) {
                        t.set(q, new Token(Kind.SPACE, ""));
                    }
                    expr = "mat4(1.0)";
                }
                if (expr != null) {
                    t.set(i, new Token(Kind.IDENT, "(" + expr + ")"));
                    continue;
                }
            }
            if ((elements != null || terrain != null) && name.equals("main") && (vertex || out != null)) {
                t.set(i, new Token(Kind.IDENT, "luxium_pack_main"));   // wrapped by the epilogue below
                continue;
            }
            if (terrain != null && vertex) {
                String expr = terrainExpression(name);
                if (expr != null) {
                    t.set(i, new Token(Kind.IDENT, "(" + expr + ")"));
                    continue;
                }
            }
            if (renames.containsKey(name)) {
                t.set(i, new Token(Kind.IDENT, renames.get(name)));
                continue;
            }
            if (dynamic) {
                if (name.equals("gl_TextureMatrix")) {
                    // gl_TextureMatrix [ 0 ] -> the draw's TextureMat; other indices stay in the block.
                    int open = nextCode(t, i + 1);
                    int num = open < 0 ? -1 : nextCode(t, open + 1);
                    int close = num < 0 ? -1 : nextCode(t, num + 1);
                    if (open >= 0 && t.get(open).is("[") && num >= 0 && t.get(num).is("0") && close >= 0 && t.get(close).is("]")) {
                        for (int q = i + 1; q <= close; q++) {
                            t.set(q, new Token(Kind.SPACE, ""));
                        }
                        // In a GLINT pipeline TextureMat scrolls the glint, not the albedo
                        // (Iris VanillaTransformer: mat4(1.0) for glint draws with an overlay).
                        t.set(i, new Token(Kind.IDENT, format.glint() ? "mat4(1.0)" : "(TextureMat)"));
                        continue;
                    }
                }
                String mv = format.shadow() ? "(" + SHADOW_FIX + " * ModelViewMat)" : "ModelViewMat";
                String expr = switch (name) {
                    case "gl_ModelViewMatrix" -> mv;
                    case "gl_NormalMatrix" -> "transpose(inverse(mat3(" + mv + ")))";
                    case "gl_ModelViewProjectionMatrix" -> "(luxium_ProjectionMatrix * " + mv + ")";
                    default -> null;
                };
                if (expr != null) {
                    t.set(i, new Token(Kind.IDENT, "(" + expr + ")"));
                    continue;
                }
            }
            if (elements != null && vertex) {
                String expr = switch (name) {
                    case "gl_Vertex" -> "vec4(Position, 1.0)";
                    case "gl_Color" -> elements.contains("Color") ? (dynamic ? "(Color * ColorModulator)" : "(Color * luxium_ColorModulator)")
                            : dynamic ? "ColorModulator" : "luxium_ColorModulator";
                    // Iris VanillaTransformer: the light pair, or full light where the format has none.
                    case "gl_MultiTexCoord1", "gl_MultiTexCoord2" -> elements.contains("UV2") ? "vec4(vec2(UV2), 0.0, 1.0)" : "vec4(240.0, 240.0, 0.0, 1.0)";
                    case "gl_MultiTexCoord0" -> elements.contains("UV0") ? "vec4(UV0, 0.0, 1.0)" : "vec4(0.0, 0.0, 0.0, 1.0)";
                    case "gl_Normal" -> elements.contains("Normal") ? "Normal" : "vec3(0.0, 0.0, 1.0)";
                    default -> name.matches("gl_MultiTexCoord[1-7]") ? "vec4(0.0, 0.0, 0.0, 1.0)" : null;
                };
                if (expr != null) {
                    t.set(i, new Token(Kind.IDENT, "(" + expr + ")"));
                    continue;
                }
            }
            String renamed = FUNCTION_RENAMES.get(name);
            if (renamed == null && BUILTIN_UNIFORMS.containsKey(name)) {
                renamed = BUILTIN_UNIFORMS.get(name).name();
            }
            if (renamed == null && vertex && BUILTIN_INPUTS.containsKey(name)) {
                renamed = BUILTIN_INPUTS.get(name).name();
            }
            if (name.equals("gl_VertexID")) {
                renamed = "gl_VertexIndex";
            }
            if (name.equals("gl_InstanceID")) {
                renamed = "gl_InstanceIndex";
            }
            if (!vertex && name.equals("gl_FragColor")) {
                renamed = "luxium_FragData0";
                outputsUsed.add(0);
            }
            if (!vertex && name.equals("gl_FragData")) {
                // gl_FragData [ N ]  ->  luxium_FragDataN
                int open = nextCode(t, i + 1);
                int num = open < 0 ? -1 : nextCode(t, open + 1);
                int close = num < 0 ? -1 : nextCode(t, num + 1);
                if (open < 0 || !t.get(open).is("[") || num < 0 || t.get(num).kind() != Kind.NUMBER
                        || close < 0 || !t.get(close).is("]")) {
                    throw new TranslationException("gl_FragData with an index that isn't a plain number");
                }
                int index = Integer.parseInt(t.get(num).text().replaceAll("[uU]$", ""));
                outputsUsed.add(index);
                for (int q = i + 1; q <= close; q++) {
                    t.set(q, new Token(Kind.SPACE, ""));
                }
                renamed = "luxium_FragData" + index;
            }
            if (renamed != null) {
                t.set(i, new Token(Kind.IDENT, renamed));
            }
        }

        // c) Header: version, struct, uniform block, built-in inputs/outputs, helpers.
        StringBuilder h = new StringBuilder("#version 450\n");
        for (String e : s.extensions) {
            h.append(e).append('\n');
        }
        if (dynamic) {
            h.append(DYNAMIC_TRANSFORMS_BLOCK);
        }
        if (overlay) {
            Slot v = varyings.get("entityColor");
            h.append("layout(location = ").append(v.location()).append(vertex ? ") out vec4 entityColor;\n" : ") in vec4 entityColor;\n");
            if (vertex) {
                h.append("uniform sampler2D ").append(OVERLAY_SAMPLER).append(";\n");
            }
        }
        boolean glintSampling = dynamic && varyings.containsKey(GLINT_COORD);
        if (glintSampling) {
            Slot v = varyings.get(GLINT_COORD);
            h.append("layout(location = ").append(v.location()).append(vertex ? ") out vec2 " : ") in vec2 ").append(GLINT_COORD).append(";\n");
            if (!vertex) {
                h.append("uniform sampler2D ").append(GLINT_SAMPLER).append(";\n");
            }
        }
        if (uniforms.containsKey("luxium_Fog")) {
            h.append("struct LuxiumFog { vec4 color; float density; float start; float end; float scale; };\n");
        }
        if (!uniforms.isEmpty()) {
            h.append("layout(std140) uniform ").append(UNIFORM_BLOCK).append(" {\n");
            for (Uniform u : uniforms.values()) {
                h.append("    ").append(u.type()).append(' ').append(u.name()).append(u.array()).append(";\n");
            }
            h.append("};\n");
        }
        if (s.identifiers.contains("mc_hasGlint") || s.identifiers.contains("mc_sampleGlint")) {
            // Iris's inline-glint API (GlintTransformer; packs test IRIS_INLINE_GLINT): real in a
            // GLINT pipeline's fragment stage, constant everywhere else.
            boolean real = glintSampling && !vertex;
            h.append("bool mc_hasGlint() { return ").append(dynamic && format.glint()).append("; }\n");
            h.append(real
                    ? "vec3 mc_sampleGlint() { return " + GLINT_ALPHA + " * texture(" + GLINT_SAMPLER + ", " + GLINT_COORD + ").rgb; }\n"
                    : "vec3 mc_sampleGlint() { return vec3(0.0); }\n");
        }
        if (vertex) {
            for (Slot in : vertexInputs.values()) {
                if (in.name().startsWith("luxium_") || CORE_INPUTS.containsKey(in.name()) || terrain != null
                        || (elements != null && ELEMENT_TYPES.containsKey(in.name()))) {
                    h.append("layout(location = ").append(in.location()).append(") in ")
                            .append(in.type()).append(' ').append(in.name()).append(in.array()).append(";\n");
                }
            }
            if (terrain != null) {
                h.append(TERRAIN_VERTEX_HEADER);
                if (vertexInputs.containsKey("a_TexCoord")) {
                    h.append(TERRAIN_TEXCOORD);
                }
            }
            if (s.identifiers.contains("ftransform")) {
                h.append(fullscreen
                        ? "vec4 luxium_ftransform() { return (" + fsProjection(scale) + " * mat4(1.0)) * vec4(" + FS_CORNER + ", 0.0, 1.0); }\n"
                        : terrain != null
                        ? "vec4 luxium_ftransform() { return luxium_ModelViewProjectionMatrix * luxium_terrain_vertex(); }\n"
                        : dynamic
                        ? "vec4 luxium_ftransform() { return luxium_ProjectionMatrix * " + (format.shadow() ? SHADOW_FIX + " * " : "")
                        + "ModelViewMat * vec4(Position, 1.0); }\n"
                        : elements != null
                        ? "vec4 luxium_ftransform() { return luxium_ModelViewProjectionMatrix * vec4(Position, 1.0); }\n"
                        : "vec4 luxium_ftransform() { return luxium_ModelViewProjectionMatrix * luxium_Vertex; }\n");
            }
        } else {
            for (int index : outputsUsed) {
                h.append("layout(location = ").append(index).append(") out vec4 luxium_FragData").append(index).append(";\n");
                fragmentOutputs.add(new Slot("luxium_FragData" + index, "vec4", "", index));
            }
            if (out != null && out.coverage()) {
                int location = coverageLocation(s.tokens, outputsUsed);
                h.append("layout(location = ").append(location).append(") out vec4 ").append(COVERAGE_OUTPUT).append(";\n");
                fragmentOutputs.add(new Slot(COVERAGE_OUTPUT, "vec4", "", location));
            }
        }
        if (s.identifiers.contains("sampler2DShadow")) {
            h.append(SHADOW_COMPARE);
        }
        if (s.identifiers.contains("shadow2D")) {
            h.append("vec4 luxium_shadow2D(sampler2D s, vec3 c) { return vec4(luxium_shadowCompare(s, c)); }\n");
        }
        if (s.identifiers.contains("shadow2DLod")) {
            h.append("vec4 luxium_shadow2DLod(sampler2D s, vec3 c, float lod) { return vec4(luxium_shadowCompare(s, c)); }\n");
        }
        String body = GlslTokenizer.join(t);
        if (terrain != null && vertex) {
            // Prologue: the pack's mesh attributes, decoded from Luxium's chunk vertex. Epilogue: the
            // reversed-Z depth fix, as in format mode below.
            StringBuilder main = new StringBuilder("\nvoid main() {\n");
            for (Declaration d : s.declarations) {
                if (d.storage().equals("attribute") || d.storage().equals("in")) {
                    for (Declarator x : d.declarators()) {
                        String v = terrainAttribute(x.name());
                        if (v != null && vertexInputs.containsKey(terrainElementFor(x.name()))) {
                            main.append("    ").append(x.name()).append(" = ").append(fromVec4(d.type(), v)).append(";\n");
                        }
                    }
                }
            }
            // The scene is reversed-Z (see format mode below). The shadow map keeps OpenGL's forward
            // depth, 0 nearest the light (Vitrail sky-and-shadows.md: "Shadow depth uses the
            // opposite convention from the scene"), because the pack reads it as written.
            main.append(terrain.shadow()
                    ? "    luxium_pack_main();\n    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n"
                    : "    luxium_pack_main();\n    gl_Position.z = (gl_Position.w - gl_Position.z) * 0.5;\n}\n");
            body += main;
        }
        if (out != null && !vertex) {
            // Epilogue: the pass's alpha test on output 0 (Iris tests what reaches draw buffer 0),
            // then the coverage mask: this fragment's depth, so the seed keeps the pack's pixel.
            StringBuilder main = new StringBuilder("\nvoid main() {\n    luxium_pack_main();\n");
            if (!Float.isNaN(out.alphaTest()) && outputsUsed.contains(0)) {
                main.append("    if (!(luxium_FragData0.a > ").append(out.alphaTest()).append(")) discard;\n");
            }
            if (out.coverage()) {
                main.append("    ").append(COVERAGE_OUTPUT).append(" = vec4(gl_FragCoord.z, 0.0, 0.0, 1.0);\n");
            }
            main.append("}\n");
            body += main;
        }
        if (elements != null && vertex) {
            // Vertex epilogue: the pack writes OpenGL clip depth (-w..w, near = -w); the pass shares
            // the game's depth buffer, which is reversed-Z in 0..1 (far = 0). z' = (w - z) / 2 maps
            // one onto the other, matching undoRevZ on the projection the pack reads.
            String prologue = overlay
                    // Iris EntityPatcher: no flash reads as rgb 0 (packs that ignore alpha rely on it).
                    ? "    vec4 luxium_overlay = texelFetch(" + OVERLAY_SAMPLER + ", UV1, 0);\n"
                    + "    entityColor = vec4(luxium_overlay.rgb, 1.0 - luxium_overlay.a);\n"
                    + "    entityColor.rgb *= float(entityColor.a != 0.0);\n"
                    : "";
            if (glintSampling) {
                String uv = vertexInputs.containsKey("UV3") ? "UV3" : "UV0";
                prologue += "    " + GLINT_COORD + " = (TextureMat * vec4(" + uv + ", 0.0, 1.0)).xy;\n";
            }
            // Reversed-Z (see the comment above). For the hand, the OpenGL depth d is first squeezed
            // to 0.4375 + 0.125 d (Iris HandRenderer); stored reversed that's 0.5 w - 0.0625 z.
            String depth = dynamic && format.shadow()
                    ? "    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n"   // the shadow map's forward depth
                    : dynamic && format.handDepth()
                    ? "    gl_Position.z = 0.5 * gl_Position.w - 0.0625 * gl_Position.z;\n"
                    : "    gl_Position.z = (gl_Position.w - gl_Position.z) * 0.5;\n";
            body += "\nvoid main() {\n" + prologue + "    luxium_pack_main();\n" + depth + "}\n";
        }
        return h + body;
    }

    /**
     * A depth comparison without a comparison sampler ({@code GpuSampler} can't describe one, and
     * Metal's {@code sample_compare} needs one). Vitrail's "arithmetic road" (glsl/GlslTranslator,
     * ofShadowCompare; see NOTICE): {@code sampler2DShadow} becomes {@code sampler2D} and each
     * lookup compares the four texels bilinear filtering would read, then blends the results with
     * the bilinear weights, which is what hardware filtering with GL_LINEAR does. The sense is
     * LEQUAL (passes when the reference is no farther than the stored depth), OptiFine's setting.
     */
    static final String SHADOW_COMPARE = """
            float luxium_shadowCompare(sampler2D s, vec3 c) {
                vec2 size = vec2(textureSize(s, 0));
                vec2 t = c.xy * size - 0.5;
                vec2 f = fract(t);
                vec4 d = textureGather(s, (floor(t) + 1.0) / size);
                vec4 r = step(vec4(c.z), d);
                return mix(mix(r.w, r.z, f.x), mix(r.x, r.y, f.x), f.y);
            }
            float luxium_shadowCompare(sampler2D s, vec3 c, float biasOrLod) { return luxium_shadowCompare(s, c); }
            """;

    /** Whether the call at {@code i} (texture(, textureLod() reads a sampler declared sampler2DShadow. */
    private static boolean firstArgumentIsShadow(List<Token> t, int i, StageScan s) {
        int open = nextCode(t, i + 1);
        if (open < 0 || !t.get(open).is("(")) {
            return false;
        }
        int arg = nextCode(t, open + 1);
        if (arg < 0 || t.get(arg).kind() != Kind.IDENT) {
            return false;
        }
        String name = t.get(arg).text();
        for (Declaration d : s.declarations) {
            if (d.type().equals("sampler2DShadow")) {
                for (Declarator x : d.declarators()) {
                    if (x.name().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Where the coverage output goes: after the pack's draw buffers and after every output it writes. */
    private static int coverageLocation(List<Token> tokens, Set<Integer> outputsUsed) {
        int after = drawBuffers(tokens).size();
        for (int used : outputsUsed) {
            after = Math.max(after, used + 1);
        }
        return after;
    }

    /** A pack attribute in terrain mode: a plain global the prologue fills, or a zero constant. */
    private static String terrainAttributes(Declaration d) {
        StringBuilder sb = new StringBuilder();
        for (Declarator x : d.declarators()) {
            sb.append(d.type()).append(' ').append(x.name()).append(x.arraySuffix());
            if (terrainAttribute(x.name()) == null) {
                sb.append(" = ").append(d.type()).append("(0)");
            }
            sb.append(";\n");
        }
        return sb.toString();
    }

    /** A sampler declaration without its unmentioned names; null keeps it as written, "" drops it. */
    private static String mentionedSamplers(Declaration d, StageScan s, Map<String, String> renames) {
        List<Declarator> kept = d.declarators().stream().filter(x -> s.mentions(x.name())).toList();
        if (kept.size() == d.declarators().size() && kept.stream().noneMatch(x -> renames.containsKey(x.name()))) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Declarator x : kept) {
            if (!d.layout().isEmpty()) {
                sb.append(d.layout()).append(' ');
            }
            sb.append("uniform ").append(d.type().equals("sampler2DShadow") ? "sampler2D" : d.type()).append(' ')
                    .append(renames.getOrDefault(x.name(), x.name())).append(x.arraySuffix()).append(";\n");
        }
        return sb.toString();
    }

    /** A pack vertex input under a game format: declared if the format carries it, else a zero constant. */
    private static String formatInputs(Declaration d, Map<String, Slot> table) {
        StringBuilder sb = new StringBuilder();
        for (Declarator x : d.declarators()) {
            Slot slot = table.get(x.name());
            if (slot != null) {
                sb.append("layout(location = ").append(slot.location()).append(") in ").append(d.type()).append(' ')
                        .append(x.name()).append(x.arraySuffix()).append(";\n");
            } else {
                sb.append(d.type()).append(' ').append(x.name()).append(x.arraySuffix()).append(" = ")
                        .append(d.type()).append("(0);\n");
            }
        }
        return sb.toString();
    }

    /** Re-declares each name of an in/out declaration on its own, with its location. */
    private static String slotDeclarations(Declaration d, String direction, Map<String, Slot> table) {
        StringBuilder sb = new StringBuilder();
        for (Declarator x : d.declarators()) {
            Slot slot = table.get(x.name());
            if (slot == null) {
                continue;
            }
            sb.append("layout(location = ").append(slot.location()).append(") ");
            if (!d.interpolation().isEmpty()) {
                sb.append(d.interpolation()).append(' ');
            }
            sb.append(direction).append(' ').append(d.type()).append(' ').append(x.name()).append(x.arraySuffix()).append(";\n");
        }
        return sb.toString();
    }

    /** Samplers and images stay outside the block: Vulkan binds them on their own. */
    static boolean isOpaque(String type) {
        return type.matches("[iu]?(sampler|image|texture|subpassInput).*") || type.equals("atomic_uint");
    }

    /** How many locations a value takes: a mat4 takes 4, an array of 3 vec2 takes 3. */
    static int slots(String type, String array, List<String> warnings) {
        int per = 1;
        Matcher m = Pattern.compile("^d?mat(\\d)").matcher(type);
        if (m.find()) {
            per = Integer.parseInt(m.group(1));
        }
        int count = 1;
        Matcher a = Pattern.compile("\\[(\\d+)]").matcher(array + type.replaceAll("^[^\\[]*", ""));
        while (a.find()) {
            count *= Integer.parseInt(a.group(1));
        }
        if ((array + type).contains("[") && !(array + type).matches(".*\\[\\d+].*")) {
            warnings.add("Array size isn't a plain number (" + type + array + "); counted as 1 location");
        }
        return per * count;
    }

    private static int nextCode(List<Token> t, int from) {
        for (int i = from; i < t.size(); i++) {
            if (!t.get(i).trivia() && !t.get(i).text().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The colortex numbers a fragment stage writes to, from its last DRAWBUFFERS or
     * RENDERTARGETS comment. A rough version for now; the exact rules (Vitrail docs/translation.md:287)
     * come with step 6, when outputs are wired to real targets.
     */
    static List<Integer> drawBuffers(List<Token> tokens) {
        List<Integer> result = List.of();
        for (Token t : tokens) {
            if (t.kind() != Kind.COMMENT) {
                continue;
            }
            Matcher m = DRAW_BUFFERS.matcher(t.text());
            if (m.find()) {
                List<Integer> list = new ArrayList<>();
                for (char c : m.group(1).toCharArray()) {
                    list.add(c - '0');
                }
                result = list;
                continue;
            }
            m = RENDER_TARGETS.matcher(t.text());
            if (m.find()) {
                List<Integer> list = new ArrayList<>();
                for (String part : m.group(1).split(",")) {
                    if (!part.isBlank()) {
                        list.add(Integer.parseInt(part.trim()));
                    }
                }
                result = list;
            }
        }
        return result;
    }
}
