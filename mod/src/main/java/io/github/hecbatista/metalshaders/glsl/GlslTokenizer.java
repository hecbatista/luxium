package io.github.hecbatista.metalshaders.glsl;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits GLSL text into tokens: names, numbers, symbols, comments, whitespace and directive
 * lines. Lossless: joining every token's text gives back the exact input, so the translator
 * can change a few tokens and leave the rest of the file as it was.
 * <p>
 * One pass, no recursion, no lookahead beyond a few characters, so its work is linear in the
 * input size whatever a pack contains. Our own code.
 */
public final class GlslTokenizer {
    public enum Kind { IDENT, NUMBER, SYMBOL, COMMENT, SPACE, DIRECTIVE }

    public record Token(Kind kind, String text) {
        public boolean is(String s) {
            return text.equals(s);
        }

        /** Whitespace and comments: tokens the compiler ignores. */
        public boolean trivia() {
            return kind == Kind.SPACE || kind == Kind.COMMENT;
        }

        @Override
        public String toString() {
            return kind + "(" + text + ")";
        }
    }

    /** Multi-character operators, longest first so "<<=" wins over "<<" and "<". */
    private static final List<String> LONG_SYMBOLS = List.of(
            "<<=", ">>=",
            "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "^^",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=");

    private GlslTokenizer() {
    }

    public static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        boolean lineStart = true;   // only whitespace so far on this line
        while (i < n) {
            char c = src.charAt(i);
            int start = i;

            if (c == '#' && lineStart) {
                // A directive runs to the end of the line, with backslash-newline continuing it.
                while (i < n && src.charAt(i) != '\n') {
                    i += (src.charAt(i) == '\\' && i + 1 < n && src.charAt(i + 1) == '\n') ? 2 : 1;
                }
                out.add(new Token(Kind.DIRECTIVE, src.substring(start, i)));
                continue;
            }
            if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(src.charAt(i))) {
                    if (src.charAt(i) == '\n') {
                        lineStart = true;
                    }
                    i++;
                }
                out.add(new Token(Kind.SPACE, src.substring(start, i)));
                continue;
            }
            lineStart = false;
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                out.add(new Token(Kind.COMMENT, src.substring(start, i)));
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;   // an unclosed comment runs to the end, like a compiler reads it
                out.add(new Token(Kind.COMMENT, src.substring(start, i)));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, src.substring(start, i)));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                i = scanNumber(src, i);
                out.add(new Token(Kind.NUMBER, src.substring(start, i)));
                continue;
            }
            String longSymbol = matchLongSymbol(src, i);
            if (longSymbol != null) {
                i += longSymbol.length();
                out.add(new Token(Kind.SYMBOL, longSymbol));
                continue;
            }
            // A single symbol, or a character GLSL doesn't use: keep it as its own token
            // so the text survives and the compiler can report it.
            i++;
            out.add(new Token(Kind.SYMBOL, src.substring(start, i)));
        }
        return out;
    }

    /** 12, 1.5, .5, 1e-3, 2.0f, 1.0lf, 0x1F, 10u ... */
    private static int scanNumber(String s, int i) {
        int n = s.length();
        if (s.charAt(i) == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            i += 2;
            while (i < n && Character.digit(s.charAt(i), 16) >= 0) {
                i++;
            }
        } else {
            while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int j = i + 1;
                if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                    j++;
                }
                if (j < n && Character.isDigit(s.charAt(j))) {
                    i = j;
                    while (i < n && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
            }
        }
        // Suffixes: u/U, f/F, lf/LF.
        while (i < n && "uUfFlL".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }

    private static String matchLongSymbol(String s, int i) {
        for (String symbol : LONG_SYMBOLS) {
            if (s.startsWith(symbol, i)) {
                return symbol;
            }
        }
        return null;
    }

    /** Joins tokens back into text. */
    public static String join(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            out.append(token.text());
        }
        return out.toString();
    }
}
