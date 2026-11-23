package io.github.hecbatista.metalshaders.glsl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NameFixes, through the translator: BSL v10.1.8's three patterns, reduced. */
class NameFixesTest {
    private static final String VERT = """
            #version 120
            varying vec2 texCoord;
            void main() {
                texCoord = gl_MultiTexCoord0.xy;
                gl_Position = ftransform();
            }
            """;

    @Test
    void samplerNamedTextureBecomesGtexture() throws Exception {
        String frag = """
                #version 120
                uniform sampler2D texture;
                uniform sampler2D shadowcolor0;
                varying vec2 texCoord;
                vec4 sampleIt(sampler2D texture, vec2 uv) { return texture2D(texture, uv); }
                void main() {
                    vec3 s = texture(shadowcolor0, texCoord).rgb;
                    gl_FragData[0] = texture(texture, texCoord) * sampleIt(texture, texCoord) * vec4(s, 1.0);
                }
                """;
        ProgramTranslator.Result r = ProgramTranslator.translate(VERT, frag);
        String f = r.fragment();
        assertTrue(f.contains("uniform sampler2D gtexture;"), f);
        assertTrue(f.contains("texture(gtexture, texCoord)"), f);
        assertTrue(f.contains("texture(shadowcolor0, texCoord)"), f);
        assertTrue(f.contains("sampleIt(gtexture, texCoord)"), f);
        assertTrue(r.samplers().stream().anyMatch(s -> s.name().equals("gtexture")));
        assertFalse(r.samplers().stream().anyMatch(s -> s.name().equals("texture")));
    }

    @Test
    void stageWithBothNamesIsLeftAlone() {
        List<GlslTokenizer.Token> out = NameFixes.apply(GlslTokenizer.tokenize(
                "uniform sampler2D texture; uniform sampler2D gtexture;"));
        assertEquals("uniform sampler2D texture; uniform sampler2D gtexture;", join(out));
    }

    @Test
    void cppAndMetalWordsAreRenamed() throws Exception {
        String frag = """
                #version 120
                varying vec2 texCoord;
                uniform float and;
                float fast(float x) { return x; }
                void main() {
                    bool new = texCoord.x > 0.5;
                    float float2 = fast(and);
                    gl_FragData[0] = vec4(new ? float2 : 0.0);
                }
                """;
        ProgramTranslator.Result r = ProgramTranslator.translate(VERT, frag);
        String f = r.fragment();
        assertTrue(f.contains("bool luxium_new = "), f);
        assertTrue(f.contains("luxium_new ? luxium_float2"), f);
        assertTrue(f.contains("float luxium_fast(float x)") && f.contains("luxium_fast(luxium_and)"), f);
        assertTrue(r.uniforms().stream().anyMatch(u -> u.name().equals("luxium_and")));
        // The uniform's value is still looked up under the pack's own name.
        assertEquals("and", NameFixes.packName("luxium_and"));
        assertEquals("luxium_ModelViewMatrix", NameFixes.packName("luxium_ModelViewMatrix"));
    }

    @Test
    void ordinaryNamesAndSwizzlesAreUntouched() {
        String src = "vec4 n = newColor.xyzw; float notNew = andValue + texture2D(t, uv).r; int ok = 2;";
        assertEquals(src, join(NameFixes.apply(GlslTokenizer.tokenize(src))));
    }

    @Test
    void legacyTextureFunctionsAreRenamed() throws Exception {
        String frag = """
                #version 120
                #extension GL_ARB_shader_texture_lod : enable
                uniform sampler2D gtexture;
                varying vec2 texCoord;
                void main() {
                    vec2 dx = dFdx(texCoord), dy = dFdy(texCoord);
                    vec4 a = texture2DGradARB(gtexture, texCoord, dx, dy);
                    vec4 b = texelFetch2D(gtexture, ivec2(0), 0);
                    gl_FragData[0] = a + b + vec4(textureSize2D(gtexture, 0), 0.0, 0.0);
                }
                """;
        String f = ProgramTranslator.translate(VERT, frag).fragment();
        assertTrue(f.contains("textureGrad(gtexture"), f);
        assertTrue(f.contains("texelFetch(gtexture"), f);
        assertTrue(f.contains("textureSize(gtexture"), f);
    }

    private static String join(List<GlslTokenizer.Token> tokens) {
        StringBuilder b = new StringBuilder();
        tokens.forEach(t -> b.append(t.text()));
        return b.toString();
    }
}
