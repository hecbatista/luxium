package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Kind;
import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fragment outputs with fewer than four components, widened to four. OpenGL lets a pack write a
 * {@code vec3} to an RGBA target (Photon v1.4: {@code layout(location = 0) out vec3
 * fragment_color;} in final, and most of its composites); Metal refuses to build such a pipeline
 * ("fragment shader color output does not have enough components for the pixel format").
 * <p>
 * The pack's output becomes a plain global of its own type, the real output is a vec4
 * {@code luxium_po_<name>} at the same location, and a new {@code main} calls the pack's (renamed
 * {@code luxium_po_main}) and then writes the value with 0 in the missing colour components and 1
 * in alpha. OpenGL leaves those components undefined, so any value is as right as another. Only
 * outputs declared on their own ({@code layout(location = N) out vec3 name;}, or a single
 * {@code out vec3 name;} without a layout, which OpenGL puts at location 0) are handled. Our own
 * code.
 */
public final class FragmentOutputs {
    private static final String PREFIX = "luxium_po_";
    private static final Pattern NARROW = Pattern.compile("(i|u|)(vec[23]|float|int|uint)");

    private record Output(int start, int end, int location, String type, String name) {
    }

    private FragmentOutputs() {
    }

    public static String widen(String fragment) {
        if (!fragment.contains("out")) {
            return fragment;
        }
        List<Token> t = GlslTokenizer.tokenize(fragment);
        List<Integer> code = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (!t.get(i).trivia() && t.get(i).kind() != Kind.DIRECTIVE && !t.get(i).text().isEmpty()) {
                code.add(i);
            }
        }
        List<Output> narrow = new ArrayList<>();
        int outputs = 0;
        int depth = 0;
        int statementStart = 0;
        for (int c = 0; c < code.size(); c++) {
            Token tok = t.get(code.get(c));
            if (tok.is("{")) {
                depth++;
            } else if (tok.is("}")) {
                depth = Math.max(0, depth - 1);
                if (depth == 0) {
                    statementStart = c + 1;
                }
            } else if (depth == 0 && tok.is(";")) {
                List<String> words = new ArrayList<>();
                for (int k = statementStart; k < c; k++) {
                    words.add(t.get(code.get(k)).text());
                }
                int location = -1;
                int w = 0;
                // layout ( location = N )
                if (words.size() >= 6 && words.get(0).equals("layout") && words.get(1).equals("(") && words.get(2).equals("location")
                        && words.get(3).equals("=") && words.get(4).matches("\\d+") && words.get(5).equals(")")) {
                    location = Integer.parseInt(words.get(4));
                    w = 6;
                }
                if (words.size() == w + 3 && words.get(w).equals("out")) {
                    outputs++;
                    Matcher m = NARROW.matcher(words.get(w + 1));
                    if (m.matches() && words.get(w + 2).matches("[A-Za-z_]\\w*")) {
                        narrow.add(new Output(code.get(statementStart), code.get(c), location, words.get(w + 1), words.get(w + 2)));
                    }
                } else if (words.contains("out") && !words.contains("(") ) {
                    outputs++;
                }
                statementStart = c + 1;
            }
        }
        boolean single = outputs == 1;
        narrow.removeIf(o -> o.location() < 0 && !single);
        if (narrow.isEmpty()) {
            return fragment;
        }
        List<Token> out = new ArrayList<>(t);
        StringBuilder copies = new StringBuilder();
        for (Output o : narrow) {
            int location = Math.max(o.location(), 0);
            for (int i = o.start(); i <= o.end(); i++) {
                out.set(i, new Token(Kind.SPACE, ""));
            }
            String scalar = o.type().startsWith("i") ? "int" : o.type().startsWith("u") ? "uint" : "float";
            String vec4 = o.type().startsWith("i") ? "ivec4" : o.type().startsWith("u") ? "uvec4" : "vec4";
            out.set(o.start(), new Token(Kind.SPACE, o.type() + " " + o.name() + ";\nlayout(location = " + location + ") out "
                    + vec4 + " " + PREFIX + o.name() + ";\n"));
            int n = o.type().matches(".*vec2") ? 2 : o.type().matches(".*vec3") ? 3 : 1;
            String zero = scalar + "(0)";
            String one = scalar + "(1)";
            String fill = switch (n) {
                case 1 -> ", " + zero + ", " + zero + ", " + one;
                case 2 -> ", " + zero + ", " + one;
                default -> ", " + one;
            };
            copies.append("    ").append(PREFIX).append(o.name()).append(" = ").append(vec4).append('(').append(o.name())
                    .append(fill).append(");\n");
        }
        depth = 0;
        boolean renamed = false;
        for (int c = 0; c + 2 < code.size(); c++) {
            Token tok = t.get(code.get(c));
            if (tok.is("{")) {
                depth++;
            } else if (tok.is("}")) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && tok.is("void") && t.get(code.get(c + 1)).is("main") && t.get(code.get(c + 2)).is("(")) {
                out.set(code.get(c + 1), new Token(Kind.IDENT, PREFIX + "main"));
                renamed = true;
            }
        }
        if (!renamed) {
            return fragment;
        }
        return GlslTokenizer.join(out) + "\nvoid main() {\n    " + PREFIX + "main();\n" + copies + "}\n";
    }
}
