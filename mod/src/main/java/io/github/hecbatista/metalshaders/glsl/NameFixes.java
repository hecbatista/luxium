package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Kind;
import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renames that make a pack's own names legal before the rest of translation sees them. Runs on
 * each stage's tokens, so every later step (declarations, sampler lists, bindings) sees the new
 * names.
 * <ul>
 *   <li><b>A sampler named {@code texture}</b> (OptiFine's old name for the albedo atlas, e.g.
 *   BSL's {@code uniform sampler2D texture;}) hides GLSL 1.30+'s {@code texture()} function.
 *   Every use that isn't a call becomes {@code gtexture}, the name Luxium binds the atlas under.
 *   Iris does the same (CommonTransformer, "rename all uses of texture ... to gtexture if it's not
 *   used as a function call"; see NOTICE). Unlike Iris, this doesn't merge a stage that declares
 *   both {@code texture} and {@code gtexture}: such a stage is left alone.</li>
 *   <li><b>C++ and Metal words</b> that GLSL allows as names (BSL's {@code bool new = ...}).
 *   spirv-cross passes them through to MSL, where Apple's compiler rejects them. They become
 *   {@code luxium_<name>}. The list was measured with this machine's glslang, spirv-cross and
 *   {@code xcrun metal}: each word as a local, a global and a function name.</li>
 *   <li><b>Vulkan GLSL type words</b> that OpenGL GLSL leaves free (Bliss's
 *   {@code vec3 skyFromTex(vec3 pos, sampler2D sampler)}). The translated stage is compiled as
 *   Vulkan GLSL, where glslang rejects them as names; they become {@code luxium_<name>} too.</li>
 *   <li><b>A pack function named like a built-in</b> in a stage written for GLSL 1.20 or older
 *   (Bliss's {@code float tanh(float x)} under {@code #version 120}). There a user function hides
 *   every built-in of that name (tanh wasn't even a built-in before 1.30); from 1.30 on, and so
 *   under the translator's {@code #version 450}, redefining one is an error. The function and
 *   every use of its name in the stage become {@code luxium_<name>}, which keeps 1.20's meaning.</li>
 * </ul>
 * Our own code; the {@code texture} rule follows Iris's behaviour.
 */
public final class NameFixes {
    /** C++ keywords GLSL doesn't reserve: illegal in MSL wherever they appear. */
    private static final Set<String> CPP_WORDS = Set.of(
            "new", "delete", "auto", "char", "catch", "try", "throw", "friend", "operator", "private",
            "protected", "virtual", "explicit", "mutable", "register", "typename", "typeid", "nullptr",
            "constexpr", "consteval", "constinit", "decltype", "noexcept", "alignas", "alignof",
            "and", "or", "xor", "bitand", "bitor", "compl", "and_eq", "or_eq", "xor_eq", "not_eq",
            "static_assert", "thread_local", "const_cast", "dynamic_cast", "reinterpret_cast", "static_cast",
            "export", "wchar_t", "char8_t", "char16_t", "char32_t", "concept", "requires",
            "co_await", "co_return", "co_yield", "ray_data");

    /**
     * Metal type and address-space names: fine as a variable (spirv-cross renames or tolerates
     * those) but not as a function name. Renamed everywhere, so a function and its calls agree.
     */
    private static final Set<String> METAL_WORDS = Set.of(
            "device", "constant", "threadgroup", "thread", "uchar", "ushort", "ulong", "size_t", "ptrdiff_t",
            "metal", "simd", "mesh", "uint8_t", "int8_t", "uint16_t", "int16_t", "uint32_t", "int32_t",
            "uint64_t", "int64_t", "atomic", "fast", "texture1d", "texture1d_array", "texture2d",
            "texture2d_array", "texture2d_ms", "texture3d", "texturecube", "texturecube_array",
            "texture_buffer", "depth2d", "depth2d_array", "depth2d_ms", "depthcube", "depthcube_array",
            // Sampling options spirv-cross writes as calls (sample(s, uv, bias(b))): a pack function
            // of the same name is ambiguous with metal::bias (Bliss's float bias()).
            "bias", "level", "gradient2d", "gradient3d", "gradientcube", "min_lod_clamp");

    /**
     * Type words of Vulkan GLSL (GL_KHR_vulkan_glsl) that OpenGL GLSL doesn't reserve, so packs use
     * them as names. Measured with this machine's glslang as a global name. Vulkan's texture types
     * (texture2D, textureCube...) are left out: in OpenGL GLSL those names are calls, which the
     * translator renames.
     */
    private static final Set<String> VULKAN_WORDS = Set.of("sampler", "samplerShadow", "subpassInput", "subpassInputMS");

    /** Metal's vector and matrix type names (float2, half3x3, uchar4...). */
    private static final Pattern METAL_VECTOR = Pattern.compile(
            "(float|half|int|uint|short|ushort|char|uchar|bool|long|ulong)[234](x[234])?");

    /** GLSL 4.50's built-in non-texture functions (GLSL 4.60 spec, chapter 8.1-8.8 and 8.13). */
    private static final Set<String> BUILTIN_FUNCTIONS = Set.of(
            "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
            "asinh", "acosh", "atanh", "pow", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt",
            "abs", "sign", "floor", "trunc", "round", "roundEven", "ceil", "fract", "mod", "modf", "min",
            "max", "clamp", "mix", "step", "smoothstep", "isnan", "isinf", "floatBitsToInt",
            "floatBitsToUint", "intBitsToFloat", "uintBitsToFloat", "fma", "frexp", "ldexp",
            "packUnorm2x16", "packSnorm2x16", "packUnorm4x8", "packSnorm4x8", "unpackUnorm2x16",
            "unpackSnorm2x16", "unpackUnorm4x8", "unpackSnorm4x8", "packHalf2x16", "unpackHalf2x16",
            "packDouble2x32", "unpackDouble2x32", "length", "distance", "dot", "cross", "normalize",
            "faceforward", "reflect", "refract", "matrixCompMult", "outerProduct", "transpose",
            "determinant", "inverse", "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual",
            "equal", "notEqual", "any", "all", "not", "uaddCarry", "usubBorrow", "umulExtended",
            "imulExtended", "bitfieldExtract", "bitfieldInsert", "bitfieldReverse", "bitCount",
            "findLSB", "findMSB", "dFdx", "dFdy", "fwidth", "noise1", "noise2", "noise3", "noise4");

    static final String PREFIX = "luxium_";

    private NameFixes() {
    }

    static List<Token> apply(List<Token> tokens) {
        List<Token> t = new ArrayList<>(tokens);
        boolean renameTexture = false;
        boolean hasGtexture = false;
        for (int i = 0; i < t.size(); i++) {
            Token tok = t.get(i);
            if (tok.kind() != Kind.IDENT) {
                continue;
            }
            if (tok.is("gtexture")) {
                hasGtexture = true;
            } else if (tok.is("texture") && !isCall(t, i)) {
                renameTexture = true;
            }
        }
        renameTexture &= !hasGtexture;
        Set<String> hidden = declaredVersion(t) < 130 ? definedBuiltins(t) : Set.of();
        for (int i = 0; i < t.size(); i++) {
            Token tok = t.get(i);
            if (tok.kind() != Kind.IDENT) {
                continue;
            }
            String name = tok.text();
            if (renameTexture && name.equals("texture") && !isCall(t, i)) {
                t.set(i, new Token(Kind.IDENT, "gtexture"));
            } else if (isReserved(name) || hidden.contains(name)) {
                t.set(i, new Token(Kind.IDENT, PREFIX + name));
            }
        }
        return t;
    }

    /**
     * The name the pack wrote, for a name this class renamed (a uniform called {@code and} is
     * {@code luxium_and} in the block, but its value is looked up as {@code and}).
     */
    public static String packName(String name) {
        if (name.startsWith(PREFIX) && isReserved(name.substring(PREFIX.length()))) {
            return name.substring(PREFIX.length());
        }
        return name;
    }

    static boolean isReserved(String name) {
        return CPP_WORDS.contains(name) || METAL_WORDS.contains(name) || VULKAN_WORDS.contains(name) || METAL_VECTOR.matcher(name).matches();
    }

    /** The number in the stage's {@code #version} line; 110 (GLSL's default) without one. */
    static int declaredVersion(List<Token> t) {
        for (Token tok : t) {
            if (tok.kind() == Kind.DIRECTIVE && tok.text().startsWith("#version")) {
                java.util.regex.Matcher m = Pattern.compile("#version\\s+(\\d+)").matcher(tok.text());
                return m.find() ? Integer.parseInt(m.group(1)) : 110;
            }
        }
        return 110;
    }

    /**
     * Built-in names the stage defines as functions at file scope: a type name, the name, then
     * "(", outside any braces.
     */
    private static Set<String> definedBuiltins(List<Token> t) {
        Set<String> out = new java.util.HashSet<>();
        int depth = 0;
        Token previous = null;
        for (int i = 0; i < t.size(); i++) {
            Token tok = t.get(i);
            if (tok.trivia() || tok.kind() == Kind.DIRECTIVE) {
                continue;
            }
            if (tok.is("{")) {
                depth++;
            } else if (tok.is("}")) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && tok.kind() == Kind.IDENT && BUILTIN_FUNCTIONS.contains(tok.text())
                    && previous != null && previous.kind() == Kind.IDENT && isCall(t, i)) {
                out.add(tok.text());
            }
            previous = tok;
        }
        return out;
    }

    /** Whether the name at {@code i} is followed by "(" (comments and whitespace skipped). */
    private static boolean isCall(List<Token> t, int i) {
        for (int j = i + 1; j < t.size(); j++) {
            if (!t.get(j).trivia()) {
                return t.get(j).is("(");
            }
        }
        return false;
    }
}
