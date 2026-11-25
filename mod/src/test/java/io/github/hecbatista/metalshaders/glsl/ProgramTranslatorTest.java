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
    void fullScreenProgramsGetFixedValuesInsteadOfVertexData() throws Exception {
        ProgramTranslator.Result r = ProgramTranslator.translate("""
                #version 130
                out vec2 texCoord;
                void main() {
                    gl_Position = ftransform();
                    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                }
                """, """
                #version 130
                uniform sampler2D colortex3;
                uniform float viewWidth;
                in vec2 texCoord;
                void main() { gl_FragData[0] = texture2D(colortex3, texCoord) * gl_Color; }
                """, ProgramTranslator.Mode.FULLSCREEN);

        assertEquals(List.of(), r.vertexInputs(), "no vertex buffer for full-screen programs");
        assertFalse(r.vertex().contains("luxium_TextureMatrix"));
        assertTrue(r.vertex().contains("(mat4(1.0)) * (vec4(vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2), 0.0, 1.0))"), r.vertex());
        assertTrue(r.fragment().contains("* (vec4(1.0))"), r.fragment());
        assertEquals(List.of("viewWidth"), r.uniforms().stream().map(ProgramTranslator.Uniform::name).toList());
        assertEquals(List.of(new ProgramTranslator.Sampler("sampler2D", "colortex3")), r.samplers());
    }

    @Test
    void samplersAStageNeverMentionsAreDropped() throws Exception {
        ProgramTranslator.Result r = ProgramTranslator.translate("""
                void main() { gl_Position = vec4(0.0); }
                """, """
                uniform sampler2D colortex3, colortex4;
                uniform sampler2D shadowtex0;
                void main() { gl_FragData[0] = texture2D(colortex3, vec2(0.5)); }
                """);
        assertTrue(r.fragment().contains("uniform sampler2D colortex3;"), r.fragment());
        assertFalse(r.fragment().contains("colortex4"), r.fragment());
        assertFalse(r.fragment().contains("shadowtex0"), r.fragment());
        assertEquals(List.of(new ProgramTranslator.Sampler("sampler2D", "colortex3")), r.samplers());
    }

    @Test
    void modeComesFromTheProgramName() {
        assertEquals(ProgramTranslator.Mode.FULLSCREEN, ProgramTranslator.modeFor("final"));
        assertEquals(ProgramTranslator.Mode.FULLSCREEN, ProgramTranslator.modeFor("composite12"));
        assertEquals(ProgramTranslator.Mode.FULLSCREEN, ProgramTranslator.modeFor("deferred1"));
        assertEquals(ProgramTranslator.Mode.GBUFFERS, ProgramTranslator.modeFor("gbuffers_terrain"));
        assertEquals(ProgramTranslator.Mode.GBUFFERS, ProgramTranslator.modeFor("shadow"));
    }

    @Test
    void uniformNamedLikeTheOtherStagesGlobalGetsAPlaceholderThere() throws Exception {
        // Bliss v2.1.2's deferred: a global sunVec in the vertex stage, a uniform in the fragment.
        ProgramTranslator.Result r = ProgramTranslator.translate("""
                #version 120
                uniform vec3 sunPosition;
                vec3 sunVec = normalize(sunPosition), other;
                void main() { gl_Position = vec4(sunVec, 1.0); }
                """, """
                #version 120
                uniform vec3 sunVec;
                void main() { gl_FragData[0] = vec4(sunVec, 1.0); }
                """);
        assertTrue(r.vertex().contains("    vec3 luxium_other_sunVec;") && r.vertex().contains("vec3 sunVec = normalize(sunPosition)"),
                r.vertex());
        assertTrue(r.fragment().contains("    vec3 sunVec;"), r.fragment());
        assertTrue(r.uniforms().stream().anyMatch(u -> u.name().equals("sunVec")));
        assertEquals(java.util.Set.of("sunPosition", "vec3", "sunVec", "other", "main"),
                ProgramTranslator.fileScopeNames(GlslTokenizer.tokenize(
                        "uniform vec3 sunPosition; vec3 sunVec = normalize(sunPosition), other; void main() { float local; }")));
    }

    @Test
    void unusedFragmentInputTheVertexNeverWritesIsDropped() throws Exception {
        // Bliss v2.1.2's gbuffers_water: flat varying float Flashing, declared and never used.
        ProgramTranslator.Result r = ProgramTranslator.translate("""
                #version 120
                varying vec2 uv;
                void main() { uv = vec2(0.0); gl_Position = vec4(0.0); }
                """, """
                #version 120
                varying vec2 uv;
                flat varying float Flashing;
                varying float used;
                void main() { gl_FragData[0] = vec4(uv, used, 1.0); }
                """);
        assertFalse(r.fragment().contains("Flashing"), r.fragment());
        assertTrue(r.fragment().contains(" in vec2 uv;") && r.fragment().contains(" in float used;"), r.fragment());
    }

    @Test
    void uniformWithDifferentTypesInTheTwoStagesIsAnError() {
        assertThrows(ProgramTranslator.TranslationException.class, () -> ProgramTranslator.translate(
                "uniform float x;\nvoid main() {}", "uniform int x;\nvoid main() {}"));
    }

    @Test
    void structVaryingIsSplitIntoItsMembers() throws Exception {
        // Photon v1.4: flat out OverworldFogParameters fog_params (three vec3) before light_levels.
        String struct = "struct Fog { vec3 a; vec3 b; vec3 c; };\n";
        ProgramTranslator.Result r = ProgramTranslator.translate("#version 400\n" + struct + """
                flat out Fog fog;
                out vec2 light;
                void main() { fog.a = vec3(0.0); fog.b = vec3(0.0); fog.c = vec3(0.0); light = vec2(0.0); gl_Position = vec4(0.0); }
                """, "#version 400\n" + struct + """
                flat in Fog fog;
                in vec2 light;
                layout(location = 0) out vec4 c;
                void main() { c = vec4(fog.a + fog.b + fog.c, light.x); }
                """);
        // The game refuses struct varyings: one flat varying per member, copied in main.
        assertFalse(r.vertex().contains("out Fog"), r.vertex());
        assertTrue(r.vertex().contains("flat out vec3 luxium_sv_fog_a;"), r.vertex());
        assertTrue(r.vertex().contains("luxium_sv_fog_c = fog.c;"), r.vertex());
        assertTrue(r.fragment().contains("fog.b = luxium_sv_fog_b;"), r.fragment());
    }

    @Test
    void packFunctionNamedLikeAMetalBuiltInIsRenamed() throws Exception {
        // Photon v1.4: float length_squared(vec3 v), ambiguous with metal::length_squared.
        ProgramTranslator.Result r = ProgramTranslator.translate("""
                #version 400
                void main() { gl_Position = vec4(0.0); }
                """, """
                #version 400
                float length_squared(vec3 v) { return dot(v, v); }
                layout(location = 0) out vec4 c;
                void main() { c = vec4(length_squared(vec3(1.0))); }
                """);
        assertTrue(r.fragment().contains("luxium_length_squared(vec3(1.0))"), r.fragment());
    }

    @Test
    void coreInputThePackDeclaresIsDeclaredOnce() throws Exception {
        // Photon v1.4's gbuffers_basic: in vec4 vaColor.
        ProgramTranslator.Result r = ProgramTranslator.translate("""
                #version 400
                in vec4 vaColor;
                out vec4 tint;
                void main() { tint = vaColor; gl_Position = vec4(0.0); }
                """, """
                #version 400
                in vec4 tint;
                layout(location = 0) out vec4 c;
                void main() { c = tint; }
                """);
        assertEquals(1, r.vertex().split("in vec4 vaColor;", -1).length - 1, r.vertex());
    }

    @Test
    void narrowFragmentOutputIsWidenedToFourComponents() throws Exception {
        // Photon v1.4's final: layout(location = 0) out vec3 fragment_color (Metal refuses the pipeline).
        ProgramTranslator.Result r = ProgramTranslator.translate("""
                #version 400
                void main() { gl_Position = vec4(0.0); }
                """, """
                #version 400
                layout(location = 0) out vec3 fragment_color;
                void main() { fragment_color = vec3(1.0); }
                """, ProgramTranslator.Mode.FULLSCREEN);
        assertTrue(r.fragment().contains("out vec4 luxium_po_fragment_color;"), r.fragment());
        assertTrue(r.fragment().contains("luxium_po_fragment_color = vec4(fragment_color, float(1));"), r.fragment());
        assertFalse(r.fragment().contains("out vec3"), r.fragment());
    }
}
