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
                         List<String> warnings) {
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

        StageScan(Stage stage, String source) {
            this.stage = stage;
            this.tokens = new ArrayList<>(GlslTokenizer.tokenize(source));
            for (Token t : tokens) {
                if (t.kind() == Kind.IDENT) {
                    identifiers.add(t.text());
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
    }

    public static Result translate(String vertexSource, String fragmentSource) throws TranslationException {
        StageScan vs = new StageScan(Stage.VERTEX, vertexSource);
        StageScan fs = new StageScan(Stage.FRAGMENT, fragmentSource);
        List<String> warnings = new ArrayList<>();

        // 1. The shared uniform block: loose uniforms from both stages, plus built-ins used.
        Map<String, Uniform> uniforms = new TreeMap<>();
        for (StageScan s : List.of(vs, fs)) {
            for (Declaration d : s.declarations) {
                if (d.storage().equals("uniform") && !isOpaque(d.type())) {
                    for (Declarator x : d.declarators()) {
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
                if (s.identifiers.contains(b.getKey())) {
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
            if (s.identifiers.contains("ftransform")) {
                uniforms.putIfAbsent("luxium_ModelViewProjectionMatrix",
                        new Uniform("mat4", "luxium_ModelViewProjectionMatrix", "", null));
            }
        }

        // 2. Vertex inputs: built-ins used, then the pack's own attribute/in, each by name.
        Map<String, Slot> vertexInputs = new LinkedHashMap<>();
        int nextInput = 0;
        Map<String, Builtin> usedInputs = new TreeMap<>();
        for (Map.Entry<String, Builtin> b : BUILTIN_INPUTS.entrySet()) {
            if (vs.identifiers.contains(b.getKey()) || (b.getKey().equals("gl_Vertex") && vs.identifiers.contains("ftransform"))) {
                usedInputs.put(b.getValue().name(), b.getValue());
            }
        }
        for (Map.Entry<String, Builtin> b : CORE_INPUTS.entrySet()) {
            if (vs.identifiers.contains(b.getKey()) && !vs.declaredNames.contains(b.getKey())) {
                usedInputs.put(b.getValue().name(), b.getValue());
            }
        }
        for (Builtin b : usedInputs.values()) {
            vertexInputs.put(b.name(), new Slot(b.name(), b.type(), b.array(), nextInput));
            nextInput += slots(b.type(), b.array(), warnings);
        }
        for (Declaration d : vs.declarations) {
            if (d.storage().equals("attribute") || d.storage().equals("in")) {
                for (Declarator x : d.declarators()) {
                    vertexInputs.put(x.name(), new Slot(x.name(), d.type(), x.arraySuffix(), nextInput));
                    nextInput += slots(d.type(), x.arraySuffix(), warnings);
                }
            }
        }

        // 3. Varyings: one table for both stages, sorted by name.
        Map<String, Slot> varyingTypes = new TreeMap<>();
        Set<String> vertexOut = new LinkedHashSet<>();
        Set<String> fragmentIn = new LinkedHashSet<>();
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
        String vertex = rewrite(vs, uniforms, vertexInputs, varyings, fragmentOutputs, warnings);
        String fragment = rewrite(fs, uniforms, vertexInputs, varyings, fragmentOutputs, warnings);

        return new Result(vertex, fragment, List.copyOf(uniforms.values()), List.copyOf(vertexInputs.values()),
                List.copyOf(varyings.values()), List.copyOf(fragmentOutputs), drawBuffers(fs.tokens),
                List.copyOf(warnings));
    }

    private static String rewrite(StageScan s, Map<String, Uniform> uniforms, Map<String, Slot> vertexInputs,
                                  Map<String, Slot> varyings, List<Slot> fragmentOutputs, List<String> warnings)
            throws TranslationException {
        List<Token> t = s.tokens;
        boolean vertex = s.stage == Stage.VERTEX;

        // a) Replace declarations, last first so earlier indices stay valid.
        List<Declaration> ds = new ArrayList<>(s.declarations);
        for (int k = ds.size() - 1; k >= 0; k--) {
            Declaration d = ds.get(k);
            String replacement = switch (d.storage()) {
                case "uniform" -> isOpaque(d.type()) ? null : "";   // loose uniforms move into the block
                case "attribute", "in" -> vertex ? slotDeclarations(d, "in", vertexInputs)
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
        if (vertex) {
            for (Slot in : vertexInputs.values()) {
                if (in.name().startsWith("luxium_") || CORE_INPUTS.containsKey(in.name())) {
                    h.append("layout(location = ").append(in.location()).append(") in ")
                            .append(in.type()).append(' ').append(in.name()).append(in.array()).append(";\n");
                }
            }
            if (s.identifiers.contains("ftransform")) {
                h.append("vec4 luxium_ftransform() { return luxium_ModelViewProjectionMatrix * luxium_Vertex; }\n");
            }
        } else {
            for (int index : outputsUsed) {
                h.append("layout(location = ").append(index).append(") out vec4 luxium_FragData").append(index).append(";\n");
                fragmentOutputs.add(new Slot("luxium_FragData" + index, "vec4", "", index));
            }
        }
        if (s.identifiers.contains("shadow2D")) {
            h.append("vec4 luxium_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }\n");
        }
        if (s.identifiers.contains("shadow2DLod")) {
            h.append("vec4 luxium_shadow2DLod(sampler2DShadow s, vec3 c, float lod) { return vec4(textureLod(s, c, lod)); }\n");
        }
        return h + GlslTokenizer.join(t);
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
