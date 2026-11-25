package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Kind;
import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Varyings of a struct type, split into one varying per member. GLSL allows
 * {@code flat out OverworldFogParameters fog_params;} (Photon v1.4 passes several such structs from
 * its vertex to its fragment stages), but the game's pipeline builder refuses a struct at a
 * stage interface ("Unsupported interface variable type struct", PipelineBuilder.generateSlotMap).
 * <p>
 * Each stage keeps a plain global of the struct under the pack's name, so the pack's code is
 * unchanged; the members travel as {@code luxium_sv_<name>_<member>}. The pack's {@code main}
 * becomes {@code luxium_sv_main}: the new vertex {@code main} copies the members out after it, the
 * new fragment {@code main} copies them in before it. Members that are themselves structs aren't
 * handled (the declaration is left as it is). Our own code.
 */
public final class StructVaryings {
    private static final String PREFIX = "luxium_sv_";
    private static final Set<String> INTERPOLATION = Set.of("flat", "smooth", "noperspective", "centroid", "sample");

    private record Member(String type, String name, String array) {
    }

    private record Found(int start, int end, String interpolation, String struct, String name) {
    }

    private StructVaryings() {
    }

    /** Returns {vertex, fragment}, rewritten when either declares a struct varying. */
    public static String[] flatten(String vertex, String fragment) {
        if (!vertex.contains("struct") && !fragment.contains("struct")) {
            return new String[] {vertex, fragment};
        }
        return new String[] {flattenStage(vertex, true), flattenStage(fragment, false)};
    }

    private static String flattenStage(String source, boolean vertex) {
        List<Token> t = GlslTokenizer.tokenize(source);
        List<Integer> code = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (!t.get(i).trivia() && t.get(i).kind() != Kind.DIRECTIVE && !t.get(i).text().isEmpty()) {
                code.add(i);
            }
        }
        Map<String, List<Member>> structs = new LinkedHashMap<>();
        List<Found> found = new ArrayList<>();
        int depth = 0;
        int statementStart = 0;   // index into code of the first token of the current file-scope statement
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
                Found f = varying(t, code, statementStart, c, vertex, structs);
                if (f != null) {
                    found.add(f);
                }
                statementStart = c + 1;
            }
            if (depth == 0 && tok.is("struct") && c + 2 < code.size() && t.get(code.get(c + 1)).kind() == Kind.IDENT
                    && t.get(code.get(c + 2)).is("{")) {
                List<Member> members = members(t, code, c + 3);
                if (members != null) {
                    structs.put(t.get(code.get(c + 1)).text(), members);
                }
            }
        }
        if (found.isEmpty()) {
            return source;
        }
        List<Token> out = new ArrayList<>(t);
        StringBuilder copies = new StringBuilder();
        for (int k = found.size() - 1; k >= 0; k--) {
            Found f = found.get(k);
            StringBuilder decl = new StringBuilder(f.struct()).append(' ').append(f.name()).append(";\n");
            for (Member m : structs.get(f.struct())) {
                String flat = PREFIX + f.name() + "_" + m.name();
                if (!f.interpolation().isEmpty()) {
                    decl.append(f.interpolation()).append(' ');
                }
                decl.append(vertex ? "out " : "in ").append(m.type()).append(' ').append(flat).append(m.array()).append(";\n");
                copies.insert(0, vertex
                        ? "    " + flat + " = " + f.name() + "." + m.name() + ";\n"
                        : "    " + f.name() + "." + m.name() + " = " + flat + ";\n");
            }
            for (int i = f.start(); i <= f.end(); i++) {
                out.set(i, new Token(Kind.SPACE, ""));
            }
            out.set(f.start(), new Token(Kind.SPACE, decl.toString()));
        }
        // Rename the pack's main (a file-scope "void main (").
        boolean renamed = false;
        depth = 0;
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
            return source;
        }
        String main = vertex
                ? "\nvoid main() {\n    " + PREFIX + "main();\n" + copies + "}\n"
                : "\nvoid main() {\n" + copies + "    " + PREFIX + "main();\n}\n";
        return GlslTokenizer.join(out) + main;
    }

    /** A file-scope {@code [interp] out|in|varying Struct name;} between code[from] and code[semi]. */
    private static Found varying(List<Token> t, List<Integer> code, int from, int semi, boolean vertex,
                                 Map<String, List<Member>> structs) {
        List<String> words = new ArrayList<>();
        for (int c = from; c < semi; c++) {
            words.add(t.get(code.get(c)).text());
        }
        if (words.size() < 3) {
            return null;
        }
        String interpolation = "";
        int w = 0;
        while (w < words.size() && INTERPOLATION.contains(words.get(w))) {
            interpolation = (interpolation + " " + words.get(w)).strip();
            w++;
        }
        if (w + 3 != words.size()) {
            return null;   // more than one name, an array, a layout(...): left alone
        }
        String storage = words.get(w);
        boolean direction = storage.equals("varying") || storage.equals(vertex ? "out" : "in");
        String struct = words.get(w + 1);
        String name = words.get(w + 2);
        if (!direction || !structs.containsKey(struct) || !name.matches("[A-Za-z_]\\w*")) {
            return null;
        }
        return new Found(code.get(from), code.get(semi), interpolation, struct, name);
    }

    /** A struct's members from code[at] (just after "{"), or null for anything but plain members. */
    private static List<Member> members(List<Token> t, List<Integer> code, int at) {
        List<Member> out = new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (int c = at; c < code.size(); c++) {
            String w = t.get(code.get(c)).text();
            if (w.equals("}")) {
                return out.isEmpty() ? null : out;
            }
            if (!w.equals(";")) {
                words.add(w);
                continue;
            }
            // type name [N] (, name [N])*
            if (words.size() < 2 || !words.get(0).matches("[A-Za-z_]\\w*")) {
                return null;
            }
            String type = words.get(0);
            String name = null;
            StringBuilder array = new StringBuilder();
            for (int k = 1; k <= words.size(); k++) {
                String x = k < words.size() ? words.get(k) : ",";
                if (x.equals(",")) {
                    if (name == null) {
                        return null;
                    }
                    out.add(new Member(type, name, array.toString()));
                    name = null;
                    array.setLength(0);
                } else if (x.equals("[") || x.equals("]") || x.matches("\\d+[uU]?")) {
                    array.append(x);
                } else if (x.matches("[A-Za-z_]\\w*") && name == null) {
                    name = x;
                } else {
                    return null;
                }
            }
            words.clear();
        }
        return null;
    }
}
