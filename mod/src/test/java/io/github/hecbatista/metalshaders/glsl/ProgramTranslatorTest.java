package io.github.hecbatista.metalshaders.glsl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramTranslatorTest {
    private static final String VERT = """
            #version 130
            uniform float rainStrength;
            uniform float cloudHeight = 192.0;
            attribute vec4 mc_Entity;
            flat out vec3 upVec, sunVec;
            out mat3 tbn;
            out vec2 texCoord;
            float helper(in vec3 x) { return x.x; }
            void main() {
                texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                gl_Position = ftransform();
            }
            """;
    private static final String FRAG = """
            #version 130
            uniform sampler2D gtexture;
            uniform float viewWidth;
            uniform float rainStrength;
            flat in vec3 upVec, sunVec;
            in mat3 tbn;
            in vec2 texCoord;
            void main() {
                vec4 color = texture2D(gtexture, texCoord);
                /* DRAWBUFFERS:06 */
                gl_FragData[0] = color;
                gl_FragData[1] = vec4(viewWidth);
            }
            """;

    private static ProgramTranslator.Result translate() throws Exception {
        return ProgramTranslator.translate(VERT, FRAG);
    }

    @Test
    void bothStagesGetTheSameUniformBlock() throws Exception {
        ProgramTranslator.Result r = translate();
        List<String> names = r.uniforms().stream().map(ProgramTranslator.Uniform::name).toList();
        // Union of both stages, sorted by name, plus built-ins that were used.
        assertEquals(List.of("cloudHeight", "luxium_ModelViewProjectionMatrix", "luxium_TextureMatrix",
                "rainStrength", "viewWidth"), names);
        String vertexBlock = r.vertex().substring(r.vertex().indexOf("uniform LuxiumUniforms"), r.vertex().indexOf("};"));
        String fragmentBlock = r.fragment().substring(r.fragment().indexOf("uniform LuxiumUniforms"), r.fragment().indexOf("};"));
        assertEquals(vertexBlock, fragmentBlock);
        assertFalse(r.vertex().contains("uniform float rainStrength;"), "loose uniform should be gone");
    }

    @Test
    void uniformDefaultValueIsKept() throws Exception {
        ProgramTranslator.Uniform cloud = translate().uniforms().stream()
                .filter(u -> u.name().equals("cloudHeight")).findFirst().orElseThrow();
        assertEquals("192.0", cloud.defaultValue());
    }

    @Test
    void samplersStayOutsideTheBlock() throws Exception {
        assertTrue(translate().fragment().contains("uniform sampler2D gtexture;"));
    }

    @Test
    void varyingsGetMatchingLocationsAndMatricesTakeSeveral() throws Exception {
        ProgramTranslator.Result r = translate();
        // Sorted by name: sunVec 0, tbn 1-3 (a mat3 takes 3), texCoord 4, upVec 5.
        assertTrue(r.vertex().contains("layout(location = 0) flat out vec3 sunVec;"), r.vertex());
        assertTrue(r.vertex().contains("layout(location = 1) out mat3 tbn;"));
        assertTrue(r.vertex().contains("layout(location = 4) out vec2 texCoord;"));
        assertTrue(r.vertex().contains("layout(location = 5) flat out vec3 upVec;"));
        assertTrue(r.fragment().contains("layout(location = 0) flat in vec3 sunVec;"));
        assertTrue(r.fragment().contains("layout(location = 1) in mat3 tbn;"));
        assertTrue(r.fragment().contains("layout(location = 4) in vec2 texCoord;"));
        assertTrue(r.fragment().contains("layout(location = 5) flat in vec3 upVec;"));
    }

    @Test
    void oldBuiltinsAreReplaced() throws Exception {
        ProgramTranslator.Result r = translate();
        assertTrue(r.vertex().startsWith("#version 450\n"));
        assertFalse(r.vertex().contains("#version 130"));
        assertTrue(r.vertex().contains("luxium_TextureMatrix[0] * luxium_MultiTexCoord0"));
        assertTrue(r.vertex().contains("luxium_ftransform()"));
        assertTrue(r.fragment().contains("texture(gtexture, texCoord)"));
        assertTrue(r.fragment().contains("luxium_FragData1 = vec4(viewWidth);"));
        assertTrue(r.fragment().contains("layout(location = 1) out vec4 luxium_FragData1;"));
    }

    @Test
    void functionParametersAreNotTreatedAsInputs() throws Exception {
        assertTrue(translate().vertex().contains("float helper(in vec3 x)"));
    }

    @Test
    void vertexInputsGetLocations() throws Exception {
        List<String> inputs = translate().vertexInputs().stream()
                .map(s -> s.name() + "@" + s.location()).toList();
        assertEquals(List.of("luxium_MultiTexCoord0@0", "luxium_Vertex@1", "mc_Entity@2"), inputs);
    }

    @Test
    void drawBuffersAreRead() throws Exception {
        assertEquals(List.of(0, 6), translate().drawBuffers());
    }

    @Test
    void lastDrawBuffersCommentWins() {
        List<GlslTokenizer.Token> tokens = GlslTokenizer.tokenize("/* DRAWBUFFERS:0 */ x /* RENDERTARGETS: 3, 12 */");
        assertEquals(List.of(3, 12), ProgramTranslator.drawBuffers(tokens));
    }

    @Test
    void uniformWithDifferentTypesInTheTwoStagesIsAnError() {
        assertThrows(ProgramTranslator.TranslationException.class, () -> ProgramTranslator.translate(
                "uniform float x;\nvoid main() {}", "uniform int x;\nvoid main() {}"));
    }
}
