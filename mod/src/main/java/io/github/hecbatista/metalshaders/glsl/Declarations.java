package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds the top-level declarations in a token list: statements outside any function or
 * braces, like {@code flat out vec3 upVec, sunVec;} or {@code uniform float x = 1.0;}.
 * Only simple declarations are recognized (qualifiers, a type, then names); anything else
 * (functions, structs, blocks) is left alone. Our own code.
 */
public final class Declarations {
    /** Words that can come before the type in a declaration. */
    static final Set<String> QUALIFIERS = Set.of(
            "uniform", "in", "out", "attribute", "varying", "const",
            "flat", "noperspective", "smooth", "centroid", "sample", "invariant", "precise",
            "highp", "mediump", "lowp");
    static final Set<String> STORAGE = Set.of("uniform", "in", "out", "attribute", "varying", "const");
    static final Set<String> INTERPOLATION = Set.of("flat", "noperspective", "smooth", "centroid", "sample");

    /** One name in a declaration, like {@code sunVec} or {@code weights[4] = ...}. */
    public record Declarator(String name, String arraySuffix, String initializer) {
    }

    /**
     * One declaration statement, covering tokens [start, end] (end is the ';').
     *
     * @param layout        the layout(...) text, or "" if none
     * @param interpolation flat/noperspective/... words in order, joined by spaces
     * @param storage       uniform / in / out / attribute / varying / const
     * @param type          e.g. "vec3", "sampler2D", "float[3]"
     */
    public record Declaration(int start, int end, String layout, String interpolation, String storage,
                              String type, List<Declarator> declarators) {
    }

    private Declarations() {
    }

    /** Every simple top-level declaration that has a storage word, in file order. */
    public static List<Declaration> find(List<Token> tokens) {
        List<Declaration> out = new ArrayList<>();
        int depth = 0;           // braces
        int statementStart = 0;
        boolean hasBrace = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() == GlslTokenizer.Kind.DIRECTIVE) {
                if (depth == 0) {
                    statementStart = i + 1;
                }
                continue;
            }
            if (t.is("{")) {
                depth++;
                hasBrace = true;
            } else if (t.is("}")) {
                depth = Math.max(0, depth - 1);
                // A function body or similar just closed: what follows is a new statement.
                if (depth == 0 && !nextCodeIs(tokens, i + 1, ";")) {
                    statementStart = i + 1;
                    hasBrace = false;
                }
            } else if (t.is(";") && depth == 0) {
                if (!hasBrace) {
                    Declaration d = parse(tokens, statementStart, i);
                    if (d != null) {
                        out.add(d);
                    }
                }
                statementStart = i + 1;
                hasBrace = false;
            }
        }
        return out;
    }

    private static boolean nextCodeIs(List<Token> tokens, int from, String text) {
        for (int i = from; i < tokens.size(); i++) {
            if (!tokens.get(i).trivia()) {
                return tokens.get(i).is(text);
            }
        }
        return false;
    }

    /** Parses tokens [start, end) as a declaration ending at the ';' at {@code end}, or null. */
    static Declaration parse(List<Token> tokens, int start, int end) {
        List<Integer> code = new ArrayList<>();
        for (int i = start; i < end; i++) {
            if (!tokens.get(i).trivia()) {
                code.add(i);
            }
        }
        int p = 0;
        String layout = "";
        List<String> interpolation = new ArrayList<>();
        String storage = null;
        while (p < code.size()) {
            Token t = tokens.get(code.get(p));
            if (t.is("layout")) {
                int close = matchingParen(tokens, code, p + 1);
                if (close < 0) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                for (int q = p; q <= close; q++) {
                    sb.append(tokens.get(code.get(q)).text());
                }
                layout = sb.toString();
                p = close + 1;
            } else if (QUALIFIERS.contains(t.text())) {
                if (STORAGE.contains(t.text())) {
                    if (storage != null) {
                        return null;   // two storage words: not a shape we handle
                    }
                    storage = t.text();
                } else if (INTERPOLATION.contains(t.text())) {
                    interpolation.add(t.text());
                }
                // invariant/precise/precision words are dropped here; rare in packs
                p++;
            } else {
                break;
            }
        }
        if (storage == null || p >= code.size() || tokens.get(code.get(p)).kind() != GlslTokenizer.Kind.IDENT) {
            return null;
        }
        String type = tokens.get(code.get(p)).text();
        p++;
        // An array written on the type: float[3] x;
        while (p < code.size() && tokens.get(code.get(p)).is("[")) {
            int close = matchingBracket(tokens, code, p);
            if (close < 0) {
                return null;
            }
            type += textOf(tokens, code, p, close);
            p = close + 1;
        }

        List<Declarator> declarators = new ArrayList<>();
        while (p < code.size()) {
            Token nameToken = tokens.get(code.get(p));
            if (nameToken.kind() != GlslTokenizer.Kind.IDENT) {
                return null;
            }
            p++;
            String array = "";
            while (p < code.size() && tokens.get(code.get(p)).is("[")) {
                int close = matchingBracket(tokens, code, p);
                if (close < 0) {
                    return null;
                }
                array += textOf(tokens, code, p, close);
                p = close + 1;
            }
            String init = null;
            if (p < code.size() && tokens.get(code.get(p)).is("=")) {
                int q = p + 1;
                int nest = 0;
                while (q < code.size()) {
                    Token u = tokens.get(code.get(q));
                    if (u.is("(") || u.is("[") || u.is("{")) {
                        nest++;
                    } else if (u.is(")") || u.is("]") || u.is("}")) {
                        nest--;
                    } else if (u.is(",") && nest == 0) {
                        break;
                    }
                    q++;
                }
                init = rawText(tokens, code.get(p + 1), q < code.size() ? code.get(q) - 1 : end - 1).strip();
                p = q;
            }
            declarators.add(new Declarator(nameToken.text(), array, init));
            if (p < code.size()) {
                if (!tokens.get(code.get(p)).is(",")) {
                    return null;
                }
                p++;
            }
        }
        if (declarators.isEmpty()) {
            return null;
        }
        return new Declaration(start, end, layout, String.join(" ", interpolation), storage, type,
                List.copyOf(declarators));
    }

    private static int matchingParen(List<Token> tokens, List<Integer> code, int open) {
        if (open >= code.size() || !tokens.get(code.get(open)).is("(")) {
            return -1;
        }
        int nest = 0;
        for (int q = open; q < code.size(); q++) {
            Token t = tokens.get(code.get(q));
            if (t.is("(")) {
                nest++;
            } else if (t.is(")") && --nest == 0) {
                return q;
            }
        }
        return -1;
    }

    private static int matchingBracket(List<Token> tokens, List<Integer> code, int open) {
        int nest = 0;
        for (int q = open; q < code.size(); q++) {
            Token t = tokens.get(code.get(q));
            if (t.is("[")) {
                nest++;
            } else if (t.is("]") && --nest == 0) {
                return q;
            }
        }
        return -1;
    }

    /** Code tokens p..q joined without the whitespace between them. */
    private static String textOf(List<Token> tokens, List<Integer> code, int p, int q) {
        StringBuilder sb = new StringBuilder();
        for (int k = p; k <= q; k++) {
            sb.append(tokens.get(code.get(k)).text());
        }
        return sb.toString();
    }

    /** All tokens from index a to b, including whitespace. */
    private static String rawText(List<Token> tokens, int a, int b) {
        StringBuilder sb = new StringBuilder();
        for (int k = a; k <= b; k++) {
            sb.append(tokens.get(k).text());
        }
        return sb.toString();
    }
}
