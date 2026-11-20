package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Kind;
import io.github.hecbatista.metalshaders.glsl.GlslTokenizer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlslTokenizerTest {
    /** The tokens that matter to a compiler: no whitespace or comments. */
    private static List<String> code(String src) {
        return GlslTokenizer.tokenize(src).stream().filter(t -> !t.trivia()).map(Token::text).toList();
    }

    @Test
    void splitsADeclaration() {
        assertEquals(List.of("flat", "out", "vec3", "upVec", ",", "sunVec", ";"),
                code("flat out vec3 upVec, sunVec;"));
    }

    @Test
    void numbersStayWhole() {
        assertEquals(List.of("1.5", ".5", "1e-3", "2.0f", "0x1F", "10u", "3.0E+2"),
                code("1.5 .5 1e-3 2.0f 0x1F 10u 3.0E+2"));
    }

    @Test
    void longestOperatorWins() {
        assertEquals(List.of("a", "<<=", "b", "&&", "c", "++", "<", "d"), code("a<<=b&&c++<d"));
    }

    @Test
    void commentsAreSeparateTokens() {
        List<Token> tokens = GlslTokenizer.tokenize("x /* DRAWBUFFERS:06 */ y // note\nz");
        assertEquals(new Token(Kind.COMMENT, "/* DRAWBUFFERS:06 */"), tokens.get(2));
        assertEquals(new Token(Kind.COMMENT, "// note"), tokens.get(6));
    }

    @Test
    void directiveOnlyAtStartOfLine() {
        List<Token> tokens = GlslTokenizer.tokenize("#version 130\n  #extension A : enable\n");
        assertEquals(new Token(Kind.DIRECTIVE, "#version 130"), tokens.get(0));
        assertEquals(new Token(Kind.DIRECTIVE, "#extension A : enable"), tokens.get(2));
    }

    @Test
    void joinGivesBackTheExactText() {
        String src = """
                #version 130
                uniform float cloudHeight = 192.0; // comment
                /* block
                   comment */ void main() { gl_FragData[0] = vec4(1.0e-3, .5, 2u, 0x1F); }
                """;
        assertEquals(src, GlslTokenizer.join(GlslTokenizer.tokenize(src)));
    }

    @Test
    void unclosedCommentRunsToTheEnd() {
        List<Token> tokens = GlslTokenizer.tokenize("a /* never closed");
        assertEquals(new Token(Kind.COMMENT, "/* never closed"), tokens.getLast());
    }
}
