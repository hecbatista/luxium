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
            "texture_buffer", "depth2d", "depth2d_array", "depth2d_ms", "depthcube", "depthcube_array");

    /** Metal's vector and matrix type names (float2, half3x3, uchar4...). */
    private static final Pattern METAL_VECTOR = Pattern.compile(
            "(float|half|int|uint|short|ushort|char|uchar|bool|long|ulong)[234](x[234])?");

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
        for (int i = 0; i < t.size(); i++) {
            Token tok = t.get(i);
            if (tok.kind() != Kind.IDENT) {
                continue;
            }
            String name = tok.text();
            if (renameTexture && name.equals("texture") && !isCall(t, i)) {
                t.set(i, new Token(Kind.IDENT, "gtexture"));
            } else if (isReserved(name)) {
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
        return CPP_WORDS.contains(name) || METAL_WORDS.contains(name) || METAL_VECTOR.matcher(name).matches();
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
