package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.pack.PackPreprocessor;
import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Terrain programs translated for Sodium's chunk mesh (Luxium's extended chunk vertex). */
class TerrainTranslationTest {
    private static final String VERTEX = """
            attribute vec4 mc_Entity;
            attribute vec2 mc_midTexCoord;
            attribute vec4 at_midBlock;
            out vec2 texCoord;
            out vec2 lmCoord;
            out vec3 normal;
            flat out int mat;
            void main() {
                texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                lmCoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
                normal = gl_NormalMatrix * gl_Normal;
                mat = int(mc_Entity.x + 0.5) + int(mc_midTexCoord.x);
                gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
            }
            """;
    private static final String FRAGMENT = """
            uniform sampler2D tex;
            in vec2 texCoord;
            in vec2 lmCoord;
            in vec3 normal;
            flat in int mat;
            void main() {
                vec4 color = texture2D(tex, texCoord);
                /* DRAWBUFFERS:064 */
                gl_FragData[0] = color;
                gl_FragData[1] = vec4(lmCoord, 0.0, 1.0);
                gl_FragData[2] = vec4(normal, 1.0);
            }
            """;

    @Test
    void sodium093AddsTheCameraOffsetAfterTheFogColour() throws Exception {
        try {
            ProgramTranslator.setSodiumCameraOffset(true);
            ProgramTranslator.Result r = ProgramTranslator.translateForTerrain(VERTEX, FRAGMENT, new ProgramTranslator.Terrain(0.5f, true));
            assertTrue(r.vertex().contains("vec4 u_FogColor;\n    vec3 u_CameraOffset;\n    vec2 u_EnvironmentFog;"), r.vertex());
            assertTrue(r.vertex().contains("luxium_draw_translation(a_LightAndData.w) - u_CameraOffset, 1.0);"), r.vertex());
            compile("unit-sodium093", r);
        } finally {
            ProgramTranslator.setSodiumCameraOffset(false);
        }
        ProgramTranslator.Result old = ProgramTranslator.translateForTerrain(VERTEX, FRAGMENT, new ProgramTranslator.Terrain(0.5f, true));
        assertFalse(old.vertex().contains("u_CameraOffset"), old.vertex());
    }

    @Test
    void decodesTheChunkVertexAndAddsTheCoverageOutput() throws Exception {
        ProgramTranslator.Result r = ProgramTranslator.translateForTerrain(VERTEX, FRAGMENT, new ProgramTranslator.Terrain(0.5f, true));
        List<String> inputs = r.vertexInputs().stream().map(ProgramTranslator.Slot::name).toList();
        assertEquals(List.of("a_Position", "a_TexCoord", "a_LightAndData", "luxium_BlockId", "luxium_MidTexCoord", "luxium_NormalIn"), inputs);
        assertTrue(r.vertex().contains("layout(push_constant) uniform LuxiumSodiumRegion"), r.vertex());
        assertTrue(r.vertex().contains("mc_Entity = vec4((vec4(float(int(luxium_BlockId >> 1u) - 1)"), r.vertex());
        assertTrue(r.vertex().contains("mc_midTexCoord = vec2(("), r.vertex());
        assertTrue(r.vertex().contains("vec4 at_midBlock = vec4(0);"), "unsupported attributes become zero: " + r.vertex());
        assertTrue(r.fragment().contains("layout(location = 3) out vec4 luxium_Coverage;"), r.fragment());
        assertTrue(r.fragment().contains("if (!(luxium_FragData0.a > 0.5)) discard;"), r.fragment());
        assertEquals(List.of(0, 6, 4), r.drawBuffers());
        assertFalse(r.vertex().contains("gl_Vertex"));
        compile("unit", r);
    }

    @Test
    void solidPassHasNoAlphaTest() throws Exception {
        ProgramTranslator.Result r = ProgramTranslator.translateForTerrain(VERTEX, FRAGMENT, new ProgramTranslator.Terrain(Float.NaN, true));
        assertFalse(r.fragment().contains("discard"), r.fragment());
    }

    /**
     * The scene's depth is reversed-Z, so the pack's gl_FragCoord.z reads come back as 1 - z
     * (Iris DepthTransformer); the shadow map is forward, and the coverage mask keeps the raw value.
     */
    @Test
    void fragDepthReadsAreForwardOutsideTheShadowPass() throws Exception {
        String fragment = """
                uniform sampler2D tex;
                in vec2 texCoord;
                void main() {
                    vec3 screenPos = vec3(gl_FragCoord.xy, gl_FragCoord.z);
                    /* DRAWBUFFERS:0 */
                    gl_FragData[0] = texture2D(tex, texCoord) * screenPos.z;
                }
                """;
        String forward = "vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w)";
        ProgramTranslator.Result scene = ProgramTranslator.translateForTerrain(VERTEX, fragment, new ProgramTranslator.Terrain(0.5f, true));
        assertTrue(scene.fragment().contains("vec3 screenPos = vec3(" + forward + ".xy, " + forward + ".z);"), scene.fragment());
        assertTrue(scene.fragment().contains("luxium_Coverage = vec4(gl_FragCoord.z, 0.0, 0.0, 1.0);"), scene.fragment());
        compile("fragdepth", scene);
        ProgramTranslator.Result shadow = ProgramTranslator.translateForTerrain(VERTEX, fragment, new ProgramTranslator.Terrain(0.1f, false, true));
        assertTrue(shadow.fragment().contains("vec3 screenPos = vec3(gl_FragCoord.xy, gl_FragCoord.z);"), shadow.fragment());
    }

    /** Audit S2-03: an explicit gl_FragDepth write is OpenGL depth too, so the scene stores 1 - it; the shadow map doesn't. */
    @Test
    void explicitFragDepthWritesAreConvertedToReversedZ() throws Exception {
        String fragment = """
                uniform sampler2D tex;
                in vec2 texCoord;
                void main() {
                    /* DRAWBUFFERS:0 */
                    gl_FragData[0] = texture2D(tex, texCoord);
                    gl_FragDepth = gl_FragCoord.z * 0.5;
                }
                """;
        ProgramTranslator.Result scene = ProgramTranslator.translateForTerrain(VERTEX, fragment, new ProgramTranslator.Terrain(0.5f, true));
        assertTrue(scene.fragment().contains("luxium_packFragDepth ="), scene.fragment());
        assertTrue(scene.fragment().contains("gl_FragDepth = 1.0 - luxium_packFragDepth;"), scene.fragment());
        assertTrue(scene.fragment().contains("luxium_Coverage = vec4(gl_FragDepth,"), scene.fragment());
        compile("fragdepthwrite", scene);
        ProgramTranslator.Result shadow = ProgramTranslator.translateForTerrain(VERTEX, fragment, new ProgramTranslator.Terrain(0.1f, false, true));
        assertFalse(shadow.fragment().contains("luxium_packFragDepth"), shadow.fragment());
    }

    @Test
    void complementaryTerrainCompilesToMetal() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/");
        Path pack = packs.getFirst();
        var vsh = PackPreprocessor.preprocessProgram(pack, "/world0/gbuffers_terrain.vsh", StandardDefines.forMac());
        var fsh = PackPreprocessor.preprocessProgram(pack, "/world0/gbuffers_terrain.fsh", StandardDefines.forMac());
        ProgramTranslator.Result r = ProgramTranslator.translateForTerrain(vsh.result().text(), fsh.result().text(),
                new ProgramTranslator.Terrain(0.5f, true));
        System.out.println("gbuffers_terrain (terrain mode): inputs " + r.vertexInputs().stream().map(ProgramTranslator.Slot::name).toList()
                + ", draw buffers " + r.drawBuffers() + ", outputs " + r.fragmentOutputs().stream().map(ProgramTranslator.Slot::name).toList()
                + ", samplers " + r.samplers().stream().map(ProgramTranslator.Sampler::name).toList());
        compile(pack.getFileName().toString(), r);
    }

    @Test
    void complementaryWaterCompilesToMetal() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/");
        Path pack = packs.getFirst();
        var vsh = PackPreprocessor.preprocessProgram(pack, "/world0/gbuffers_water.vsh", StandardDefines.forMac());
        var fsh = PackPreprocessor.preprocessProgram(pack, "/world0/gbuffers_water.fsh", StandardDefines.forMac());
        ProgramTranslator.Result r = ProgramTranslator.translateForTerrain(vsh.result().text(), fsh.result().text(),
                new ProgramTranslator.Terrain(0.0001f, false));
        System.out.println("gbuffers_water (terrain mode): inputs " + r.vertexInputs().stream().map(ProgramTranslator.Slot::name).toList()
                + ", draw buffers " + r.drawBuffers() + ", outputs " + r.fragmentOutputs().stream().map(ProgramTranslator.Slot::name).toList()
                + ", samplers " + r.samplers().stream().map(ProgramTranslator.Sampler::name).toList());
        assertFalse(r.fragment().contains(ProgramTranslator.COVERAGE_OUTPUT), "water writes no coverage mask");
        compile(pack.getFileName() + "-water", r);
    }

    @Test
    void complementaryEntitiesCompileForTheEntityFormat() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/");
        Path pack = packs.getFirst();
        var vsh = PackPreprocessor.preprocessProgram(pack, "/world0/gbuffers_entities.vsh", StandardDefines.forMac());
        var fsh = PackPreprocessor.preprocessProgram(pack, "/world0/gbuffers_entities.fsh", StandardDefines.forMac());
        // DefaultVertexFormat.ENTITY's element names (26.3).
        ProgramTranslator.Result r = ProgramTranslator.translateForDraw(vsh.result().text(), fsh.result().text(),
                java.util.Set.of("Position", "Color", "UV0", "UV1", "UV2", "Normal"),
                new ProgramTranslator.Format(0.1f, true, java.util.Map.of("tex", "Sampler0", "gtexture", "Sampler0", "texture", "Sampler0")));
        System.out.println("gbuffers_entities (draw mode): inputs " + r.vertexInputs().stream().map(ProgramTranslator.Slot::name).toList()
                + ", draw buffers " + r.drawBuffers() + ", outputs " + r.fragmentOutputs().stream().map(ProgramTranslator.Slot::name).toList()
                + ", samplers " + r.samplers().stream().map(ProgramTranslator.Sampler::name).toList());
        assertTrue(r.vertex().contains("uniform DynamicTransforms"), "reads the draw's transforms");
        assertTrue(r.fragment().contains(ProgramTranslator.COVERAGE_OUTPUT), "entities write the coverage mask");
        assertTrue(r.samplers().stream().anyMatch(x -> x.name().equals("Sampler0")), "the skin is the game's Sampler0");
        compile(pack.getFileName() + "-entities", r);
    }

    private static final String GLINT_VERTEX = """
            out vec2 texCoord;
            void main() {
                texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                gl_Position = ftransform();
            }
            """;
    private static final String GLINT_FRAGMENT = """
            uniform sampler2D tex;
            in vec2 texCoord;
            void main() {
                vec4 color = texture2D(tex, texCoord);
                if (mc_hasGlint()) {
                    vec3 glint = mc_sampleGlint();
                    color.rgb += glint * glint;
                }
                /* DRAWBUFFERS:0 */
                gl_FragData[0] = color;
            }
            """;

    /**
     * In a GLINT pipeline the draw's TextureMat scrolls the glint, so the albedo's texture matrix is
     * the identity there, and mc_sampleGlint() reads GlintSampler at TextureMat * UV0 (UV3 in the
     * special-glint format). Elsewhere TextureMat stays, and the glint functions are constants.
     */
    @Test
    void glintDrawsKeepTheAlbedoStillAndSampleTheGlint() throws Exception {
        java.util.Set<String> entity = java.util.Set.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");
        java.util.Map<String, String> renames = java.util.Map.of("tex", "Sampler0");
        ProgramTranslator.Result plain = ProgramTranslator.translateForDraw(GLINT_VERTEX, GLINT_FRAGMENT, entity,
                new ProgramTranslator.Format(0.1f, true, renames, false, true, false, false));
        assertTrue(plain.vertex().contains("(TextureMat) * "), plain.vertex());
        assertTrue(plain.fragment().contains("bool mc_hasGlint() { return false; }"), plain.fragment());
        assertFalse(plain.samplers().stream().anyMatch(x -> x.name().equals(ProgramTranslator.GLINT_SAMPLER)));
        compile("glint-none", plain);

        ProgramTranslator.Result glint = ProgramTranslator.translateForDraw(GLINT_VERTEX, GLINT_FRAGMENT, entity,
                new ProgramTranslator.Format(0.1f, true, renames, false, true, false, true));
        assertTrue(glint.vertex().contains("texCoord = (mat4(1.0) * "), glint.vertex());
        assertTrue(glint.vertex().contains("luxium_GlintCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;"), glint.vertex());
        assertTrue(glint.fragment().contains("bool mc_hasGlint() { return true; }"), glint.fragment());
        assertTrue(glint.fragment().contains("texture(GlintSampler, luxium_GlintCoord)"), glint.fragment());
        assertTrue(glint.samplers().stream().anyMatch(x -> x.name().equals(ProgramTranslator.GLINT_SAMPLER)));
        assertTrue(glint.uniforms().stream().anyMatch(u -> u.name().equals(ProgramTranslator.GLINT_ALPHA)));
        compile("glint-standard", glint);

        java.util.Set<String> special = new java.util.HashSet<>(entity);
        special.add("UV3");
        ProgramTranslator.Result glintSpecial = ProgramTranslator.translateForDraw(GLINT_VERTEX, GLINT_FRAGMENT, special,
                new ProgramTranslator.Format(0.1f, true, renames, false, true, false, true));
        assertTrue(glintSpecial.vertex().contains("luxium_GlintCoord = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;"), glintSpecial.vertex());
        assertTrue(glintSpecial.vertexInputs().stream().anyMatch(x -> x.name().equals("UV3")));
        compile("glint-special", glintSpecial);
    }

    @Test
    void complementaryEntitiesAndHandCompileWithTheGlint() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/");
        Path pack = packs.getFirst();
        for (String program : List.of("gbuffers_entities", "gbuffers_hand")) {
            var vsh = PackPreprocessor.preprocessProgram(pack, "/world0/" + program + ".vsh", StandardDefines.forMac());
            var fsh = PackPreprocessor.preprocessProgram(pack, "/world0/" + program + ".fsh", StandardDefines.forMac());
            for (boolean special : List.of(false, true)) {
                java.util.Set<String> elements = new java.util.HashSet<>(java.util.Set.of("Position", "Color", "UV0", "UV1", "UV2", "Normal"));
                if (special) {
                    elements.add("UV3");
                }
                ProgramTranslator.Result r = ProgramTranslator.translateForDraw(vsh.result().text(), fsh.result().text(), elements,
                        new ProgramTranslator.Format(0.1f, program.equals("gbuffers_entities"),
                                java.util.Map.of("tex", "Sampler0", "gtexture", "Sampler0", "texture", "Sampler0"),
                                program.equals("gbuffers_hand"), true, false, true));
                assertTrue(r.fragment().contains("texture(GlintSampler, luxium_GlintCoord)"), program + " samples the glint");
                compile(pack.getFileName() + "-" + program + "-glint" + (special ? "-special" : ""), r);
            }
        }
    }

    /** glslang (Vulkan 1.2) -> spirv-cross MSL -> xcrun metal, as TranslateRealPackTest does. */
    private static void compile(String name, ProgramTranslator.Result r) throws Exception {
        assumeTrue(tool("glslangValidator") && tool("spirv-cross"), "glslang/spirv-cross not installed");
        Path dir = Files.createDirectories(Path.of("build/terrain-check"));
        for (String stage : List.of("vert", "frag")) {
            Path src = dir.resolve(name + "." + stage);
            Files.writeString(src, stage.equals("vert") ? r.vertex() : r.fragment());
            run(List.of("glslangValidator", "-V", "--target-env", "vulkan1.2", "--auto-map-bindings", "-S", stage,
                    src.toString(), "-o", src + ".spv"), src);
            // Metallum gives the push-constant block a buffer slot of its own (MetalCrossShaderCompiler
            // sets its binding); the command line can't, so let spirv-cross number the buffers itself.
            run(List.of("spirv-cross", src + ".spv", "--msl", "--msl-version", "40000",
                    "--msl-texture-buffer-native", "--output", src + ".metal"), src);
            if (tool("xcrun")) {
                run(List.of("xcrun", "metal", "-std=metal4.0", "-c", src + ".metal", "-o", src + ".air"), src);
            }
        }
    }

    private static void run(List<String> command, Path src) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), command.getFirst() + " failed on " + src + ":\n" + out);
    }

    private static boolean tool(String name) throws Exception {
        return new ProcessBuilder("which", name).start().waitFor() == 0;
    }
}
