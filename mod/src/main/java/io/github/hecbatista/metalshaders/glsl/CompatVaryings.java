package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Kind;
import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code gl_FogFragCoord}, a compatibility-profile varying that Vulkan GLSL doesn't have (MakeUp
 * writes it in every gbuffers vertex stage). As Iris's CommonTransformer does (see NOTICE): it
 * becomes {@code luxium_FogFragCoord}, an {@code out float} of the vertex stage set to 0 at the
 * top of {@code main} (so a stage that doesn't write it still hands one over) and an
 * {@code in float} of the fragment stage.
 */
public final class CompatVaryings {
    static final String FOG = "luxium_FogFragCoord";

    private CompatVaryings() {
    }

    private static final java.util.regex.Pattern ID_ATTRIBUTE = java.util.regex.Pattern.compile(
            "\\b(?:attribute|in)\\s+int\\s+(entityId|blockEntityId|currentRenderedItemId)\\s*;");

    /**
     * Returns {vertex, fragment}: gl_FogFragCoord made a varying when either stage mentions it, and
     * the entity ids declared as vertex attributes ({@code attribute int blockEntityId;}, MakeUp)
     * read as the uniforms Luxium fills per draw. Iris replaces every use of those names with its
     * per-vertex entity ids whatever the declaration (EntityPatcher), so such a pack works there.
     */
    public static String[] apply(String vertex, String fragment) {
        vertex = ID_ATTRIBUTE.matcher(vertex).replaceAll("uniform int $1;");
        if (!vertex.contains("gl_FogFragCoord") && !fragment.contains("gl_FogFragCoord")) {
            return new String[] {vertex, fragment};
        }
        return new String[] {stage(vertex, true), stage(fragment, false)};
    }

    private static String stage(String source, boolean vertex) {
        List<Token> t = new ArrayList<>(GlslTokenizer.tokenize(source));
        int firstCode = -1;
        int mainBody = -1;
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            Token tok = t.get(i);
            if (tok.kind() == Kind.IDENT && tok.is("gl_FogFragCoord")) {
                t.set(i, new Token(Kind.IDENT, FOG));
            }
            if (tok.trivia() || tok.kind() == Kind.DIRECTIVE || tok.text().isEmpty()) {
                continue;
            }
            if (firstCode < 0) {
                firstCode = i;
            }
            if (tok.is("{")) {
                if (depth == 0 && mainBody < 0 && isMainHeader(t, i)) {
                    mainBody = i;
                }
                depth++;
            } else if (tok.is("}")) {
                depth = Math.max(0, depth - 1);
            }
        }
        if (firstCode < 0) {
            return source;
        }
        if (vertex && mainBody >= 0) {
            t.add(mainBody + 1, new Token(Kind.SPACE, "\n    " + FOG + " = 0.0;\n"));
        }
        t.add(firstCode, new Token(Kind.SPACE, (vertex ? "out" : "in") + " float " + FOG + ";\n"));
        return GlslTokenizer.join(t);
    }

    /** Whether the "{" at {@code brace} opens {@code void main()}. */
    private static boolean isMainHeader(List<Token> t, int brace) {
        List<String> back = new ArrayList<>();
        for (int i = brace - 1; i >= 0 && back.size() < 5; i--) {
            if (!t.get(i).trivia() && !t.get(i).text().isEmpty()) {
                back.add(0, t.get(i).text());
            }
        }
        return back.subList(Math.max(0, back.size() - 4), back.size()).equals(List.of("void", "main", "(", ")"))
                || back.equals(List.of("void", "main", "(", "void", ")"));
    }
}
