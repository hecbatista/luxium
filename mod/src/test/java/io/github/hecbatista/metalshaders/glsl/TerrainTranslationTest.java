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
